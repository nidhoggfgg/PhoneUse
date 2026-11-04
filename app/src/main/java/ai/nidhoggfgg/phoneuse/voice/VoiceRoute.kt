package ai.nidhoggfgg.phoneuse.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import java.io.Closeable

/** System audio policy APIs. Only the process launched by adb loads these classes. */
internal class VoiceRoute(context: Context, private val uid: Int, sampleRate: Int) : Closeable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val policyType = Class.forName("android.media.audiopolicy.AudioPolicy")
    private val mixType = Class.forName("android.media.audiopolicy.AudioMix")
    private val recorderUid = AudioRecordingConfiguration::class.java.getMethod("getClientUid")
    private val policy: Any
    private val address: String
    val track: AudioTrack
    val startFrames: Int

    companion object {
        private const val RULE = "android.media.audiopolicy.AudioMixingRule"
        private fun uidRule(uid: Int): Any {
            val rule = Class.forName(RULE)
            val builder = Class.forName("$RULE\$Builder").getConstructor().newInstance()
            configureRecorderRole(builder, rule, Class.forName("android.media.audiopolicy.AudioMix"))
            builder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(builder, rule.getField("RULE_MATCH_UID").getInt(null), uid)
            return requireNotNull(builder.javaClass.getMethod("build").invoke(builder))
        }

        internal fun configureRecorderRole(builder: Any, rule: Class<*>, mix: Class<*>) {
            // Both variants select the recorder direction explicitly. Keep the UID as the only match.
            val type = builder.javaClass
            val int = Integer.TYPE
            try {
                type.getMethod("setTargetMixRole", int).invoke(builder, rule.getField("MIX_ROLE_INJECTOR").getInt(null))
            } catch (_: NoSuchMethodException) {
                val legacy = try { type.getMethod("setTargetMixType", int) }
                    catch (_: NoSuchMethodException) { error("System lacks UID-scoped recorder mix support") }
                legacy.invoke(builder, mix.getField("MIX_TYPE_RECORDERS").getInt(null))
            }
        }

        fun probe(context: Context, uid: Int): String? = try {
            check(context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) { "ADB shell lacks MODIFY_AUDIO_ROUTING" }
            uidRule(uid)
            null
        } catch (e: Exception) { e.cause?.message ?: e.message ?: "UID-scoped recorder mix unavailable" }
    }

    init {
        requireIdleTarget()
        val rule = uidRule(uid)
        val format = AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val mixBuilder = Class.forName(mixType.name + "\$Builder").getConstructor(rule.javaClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
        val policyBuilder = Class.forName(policyType.name + "\$Builder").getConstructor(Context::class.java).newInstance(context)
        policyBuilder.javaClass.getMethod("addMix", mixType).invoke(policyBuilder, mix)
        policy = requireNotNull(policyBuilder.javaClass.getMethod("build").invoke(policyBuilder))
        check(AudioManager::class.java.getMethod("registerAudioPolicy", policyType).invoke(audio, policy) == 0) {
            "System rejected the recorder mix"
        }
        try {
            address = mixType.getMethod("getRegistration").invoke(mix) as String
            check(address.isNotEmpty()) { "Mix has no device address" }
            track = policyType.getMethod("createAudioTrackSource", mixType).invoke(policy, mix) as? AudioTrack
                ?: error("No audio injection track")
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED)
                val threshold = try { AudioTrack::class.java.getMethod("setStartThresholdInFrames", Integer.TYPE) }
                    catch (_: NoSuchMethodException) { null }
                startFrames = threshold?.invoke(track, 1) as? Int ?: track.bufferSizeInFrames
                track.play()
            } catch (e: Exception) { track.release(); throw e }
        } catch (e: Exception) { unregister(); throw e }
    }
    fun requireIdleTarget() {
        check(audio.activeRecordingConfigurations.none { recorderUid.invoke(it) == uid }) { "Target already has an active recorder" }
    }
    fun recorderSession(): Int? = audio.activeRecordingConfigurations.singleOrNull {
        recorderUid.invoke(it) == uid && !it.isClientSilenced && it.audioDevice?.let { device ->
            device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX && device.address == address
        } == true
    }?.clientAudioSessionId

    override fun close() {
        try { track.release() } finally { unregister() }
    }
    private fun unregister() {
        AudioManager::class.java.getMethod("unregisterAudioPolicy", policyType).invoke(audio, policy)
    }
}
