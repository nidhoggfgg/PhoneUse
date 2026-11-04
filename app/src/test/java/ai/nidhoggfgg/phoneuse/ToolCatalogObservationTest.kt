package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONArray

class ToolCatalogObservationTest {
    @Test fun adbBridgeUsesTheSameToolSchemas() {
        val catalog = listOf(File("../tools/phoneuse_catalog.json"), File("tools/phoneuse_catalog.json")).first { it.isFile }
        assertEquals(canonical(ToolCatalog.all()), canonical(JSONArray(catalog.readText())))
    }

    @Test fun catalogDefaultsMatchImmediateIndependentCapabilities() {
        val tools = ToolCatalog.all()
        for (index in 0 until tools.length()) {
            val tool = tools.getJSONObject(index)
            val fields = tool.getJSONObject("inputSchema").getJSONObject("properties")
            if (tool.getString("name") == "observe") {
                assertEquals(0, fields.getJSONObject("stable_wait_ms").getInt("default"))
                assertEquals("decision", fields.getJSONObject("output").getString("default"))
            }
            if (fields.has("observe_after")) assertFalse(fields.getJSONObject("observe_after").getBoolean("default"))
        }
    }

    @Test fun sharedOptionsAcceptCombinedConditionsAndValidatedOutputScope() {
        val options = json("mode" to "both", "timeout_ms" to 60000, "output" to "decision", "diagnostics" to true,
            "include_details" to true, "region" to json("left" to 0, "top" to 1, "right" to 50, "bottom" to 70),
            "subtree" to json("observation_id" to "old-snapshot", "element_id" to "0.2"),
            "wait_until" to json("package_name" to "sample.app", "element" to json("view_id" to "sample.app:id/query",
                "enabled" to true, "visible" to true, "editable" to true, "action" to "set_text", "match" to "unique")))
        ToolCatalog.validate("observe", options)
        ToolCatalog.validate("home", json("request_id" to "a", "observe_after" to true, "observation_options" to options))
        ToolCatalog.validate("home", json("request_id" to "b", "observe_on_rejection" to true, "observation_options" to options))
    }

    @Test fun invalidObservationOptionsAreRejectedBeforeInputDispatch() {
        val invalid = listOf(json("timeout_ms" to 0), json("timeout_ms" to 60001), json("max_attempts" to 4),
            json("mode" to "anything"), json("output" to "compact"), json("wait_until" to json()),
            json("wait_until" to json("element" to json("element_id" to "0.1"))),
            json("wait_until" to json("element" to json("enabled" to true))),
            json("wait_until" to json("element" to json("text" to "OK", "match" to "first"))),
            json("wait_until" to json("element" to json("text" to "OK", "action" to "unknown"))),
            json("subtree" to json("element_id" to "0")),
            json("region" to json("left" to 2, "top" to 0, "right" to 1, "bottom" to 3)))
        invalid.forEach { options ->
            assertThrows(DeviceError::class.java) { ToolCatalog.validate("observe", options) }
            assertThrows(DeviceError::class.java) {
                ToolCatalog.validate("home", json("request_id" to "a", "observe_after" to true, "observation_options" to options))
            }
        }
        assertThrows(DeviceError::class.java) {
            ToolCatalog.validate("home", json("request_id" to "a", "observation_options" to json("mode" to "tree")))
        }
    }
}
