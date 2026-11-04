package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.voice.VoiceRoute
import org.junit.Assert.*
import org.junit.Test

class VoiceRouteCompatibilityTest {
    class Rule { companion object { const val MIX_ROLE_INJECTOR = 3 } }
    class Mix { companion object { const val MIX_TYPE_RECORDERS = 5 } }
    class Current { var role = 0; fun setTargetMixRole(value: Int) { role = value } }
    class Legacy { var type = 0; fun setTargetMixType(value: Int) { type = value } }

    @Test fun selectRecorderRoleByApiPresence() {
        val current = Current()
        val legacy = Legacy()
        VoiceRoute.configureRecorderRole(current, Rule::class.java, Mix::class.java)
        VoiceRoute.configureRecorderRole(legacy, Rule::class.java, Mix::class.java)
        assertEquals(Rule.MIX_ROLE_INJECTOR, current.role)
        assertEquals(Mix.MIX_TYPE_RECORDERS, legacy.type)
        assertThrows(IllegalStateException::class.java) {
            VoiceRoute.configureRecorderRole(Any(), Rule::class.java, Mix::class.java)
        }
    }
}
