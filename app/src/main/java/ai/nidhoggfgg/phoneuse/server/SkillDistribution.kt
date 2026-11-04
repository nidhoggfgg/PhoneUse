package ai.nidhoggfgg.phoneuse.server

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Public, immutable installation files only. Device control still requires pairing. */
class SkillDistribution(private val readAsset: (String) -> ByteArray, private val appVersion: String) {
    data class Resource(val bytes: ByteArray, val contentType: String, val filename: String? = null)

    private val archive by lazy { readAsset("phone-use.zip") }
    val sha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
    }
    private val skill by lazy {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            check(generateSequence { zip.nextEntry }.any { it.name == "phone-use/SKILL.md" }) {
                "Bundled phone-use/SKILL.md is missing"
            }
            zip.readBytes()
        }
    }

    fun connectUrl(origin: String) = "$origin/connect#sha256=$sha256"

    fun resource(path: String, origin: String): Resource? = when (path) {
        "/connect" -> Resource(readAsset("web/connect.md").toString(Charsets.UTF_8)
            .replace("{{ORIGIN}}", origin).toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        "/skills/manifest.json" -> Resource(JSONObject()
            .put("schema_version", 1).put("name", "phone-use").put("app_version", appVersion)
            .put("distribution", "bundled").put("sha256", sha256).put("size", archive.size)
            .put("archive", "/skills/phone-use.zip").put("entrypoint", "phone-use/SKILL.md")
            .put("skill", "/skills/phone-use/SKILL.md").put("connect_url", connectUrl(origin))
            .toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
        "/skills/phone-use.zip" -> Resource(archive, "application/zip", "phone-use.zip")
        "/skills/phone-use/SKILL.md" -> Resource(skill, "text/plain; charset=utf-8")
        else -> null
    }

    companion object {
        val paths = setOf("/connect", "/skills/manifest.json", "/skills/phone-use.zip", "/skills/phone-use/SKILL.md")
    }
}
