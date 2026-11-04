package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.SkillDistribution
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillDistributionTest {
    @Test fun manifestIdentifiesExactlyTheDownloadedArchiveAndEntryPoint() {
        val skill = "---\nname: phone-use\n---\n中文 Skill\n".toByteArray()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("phone-use/SKILL.md")); zip.write(skill); zip.closeEntry()
                zip.putNextEntry(ZipEntry("phone-use/scripts/phoneuse_client.py")); zip.write("# client".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val assets = mapOf("phone-use.zip" to archive, "web/connect.md" to "Connect {{ORIGIN}}".toByteArray())
        val distribution = SkillDistribution({ assets.getValue(it) }, "test-version")
        val origin = "https://[2001:db8::1]:8443"
        val manifest = JSONObject(distribution.resource("/skills/manifest.json", origin)!!.bytes.toString(Charsets.UTF_8))
        val download = distribution.resource(manifest.getString("archive"), origin)!!
        assertArrayEquals(archive, download.bytes)
        assertEquals("application/zip", download.contentType)
        assertEquals("phone-use.zip", download.filename)
        assertEquals(archive.size, manifest.getInt("size"))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(download.bytes).joinToString("") { "%02x".format(it) }, manifest.getString("sha256"))
        assertEquals("test-version", manifest.getString("app_version"))
        assertEquals("bundled", manifest.getString("distribution"))
        assertEquals("$origin/connect#sha256=${manifest.getString("sha256")}", distribution.connectUrl(origin))
        assertEquals(distribution.connectUrl(origin), manifest.getString("connect_url"))
        assertArrayEquals(skill, distribution.resource(manifest.getString("skill"), origin)!!.bytes)
        assertEquals("Connect $origin", distribution.resource("/connect", origin)!!.bytes.toString(Charsets.UTF_8))
        for (path in listOf("/skills/../identity", "/skills/.phoneuse.json", "/skills/phone-use/../../secret", "/skills/", "/other")) {
            assertNull(distribution.resource(path, origin))
            assertFalse(path in SkillDistribution.paths)
        }
    }
}
