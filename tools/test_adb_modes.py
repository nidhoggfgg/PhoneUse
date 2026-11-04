"""Host protocol tests. Neither a connected device nor a local adb server is needed."""
import base64
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid
import wave

import phoneuse_adb as adb
import phoneuse_client as client
from test_phoneuse_client import identity, pending


class ModeTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.connection = client.Connection(Path(directory.name) / "config.json")
        self.marker = str(uuid.uuid4())
        self.installed = self.attached = False
        self.usb = self.accessible = self.audio_route = True
        self.app = identity()
        self.inputs = []
        self.app_calls = []
        self.frame = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 20, 30) + bytes(30)
        self.start_count = 0
        self.enter(patch.object(adb, "adb", side_effect=self.adb_command))
        self.enter(patch.object(client, "identify", side_effect=self.identify))
        self.enter(patch.object(client, "post", side_effect=self.app_post))
        self.enter(patch.object(adb.AdbDevice, "start_voice_helper", self.start_helper))

    def enter(self, patcher):
        self.addCleanup(patcher.stop)
        return patcher.start()

    def adb_command(self, *args):
        if args == ("devices", "-l"):
            return b"List of devices attached\nusb device model:Pixel transport_id:42\n" if self.usb else b""
        self.assertEqual(("-t", "42"), args[:2])
        cmd = args[2:]
        if cmd[:2] == ("shell", "cat"):
            return (self.marker if cmd[2] == adb.AdbDevice.marker else "boot1").encode()
        if cmd[:2] == ("forward", "tcp:0"):
            return b"31500\n"
        if cmd[:2] == ("exec-out", "screencap"):
            return self.frame
        if cmd[:2] == ("shell", "input"):
            self.inputs.append(cmd[2:])
        if cmd == ("shell", "dumpsys", "power"):
            return b" mWakefulness=Awake\n"
        if cmd == ("shell", "dumpsys", "window", "policy"):
            return b"  showing=false\n"
        if cmd[:1] == ("shell",) and cmd[1].startswith("dumpsys window |"):
            return b"mCurrentFocus=Window{42 u0 com.example.app/.Editor}"
        return b""

    def identify(self, address, pin=None):
        if not self.installed:
            raise ConnectionRefusedError()
        return self.app.copy()

    def start_helper(self):
        self.start_count += 1
        self.attached = True
        return Mock(poll=lambda: None)

    def app_post(self, config, endpoint, payload, authenticate=True):
        if endpoint == "/pair/request":
            return pending()
        if endpoint == "/pair/reveal":
            return {**self.app, "status": "pending"}
        if endpoint == "/pair/status":
            return {**self.app, "status": "approved", "token": "paired-token", "client_id": "client"}
        self.assertEqual("/api", endpoint)
        self.assertEqual("paired-token", config["token"])
        args = payload["arguments"]
        self.assertEqual("phone-a", args["device_id"])
        self.app_calls.append((payload["tool"], args))
        if payload["tool"] == "get_capabilities":
            return {**self.app, "accessibility": self.accessible, "text_modes": ["replace", "insert"],
                    "send_voice": {"helper_attached": self.attached,
                                   "routing_api_available": self.attached and self.audio_route}}
        if payload["tool"] == "observe":
            return {**self.app, "observation_id": "app-snapshot", "screenshot": {"available": True,
                    "mime_type": "image/png", "data_base64": base64.b64encode(self.frame).decode()}}
        return {**self.app, "state": "executed", "action_executed": True}

    def pair(self, key=None):
        self.installed = True
        state = self.connection.connect("phone", device_id=key)
        self.assertTrue(state["confirmation_required"])
        self.assertEqual("paired", self.connection.connect(device_id=state["device_id"], confirm_pairing=True)["status"])
        return state["device_id"]

    def call(self, key, tool, **args):
        return self.connection.send(key, "/api", {"tool": tool, "arguments": args})

    def features(self, key, mode, selection, voice):
        caps = self.call(key, "get_capabilities")
        self.assertEqual(key, caps["device_id"])
        self.assertEqual(mode, caps["mode"])
        self.assertEqual({"selection_replace": selection, "screenshot": True, "send_voice": voice}, caps["features"])
        self.assertEqual(voice, caps["send_voice"]["routing_api_available"])

    def test_adb_first_same_id_across_pairing_detach_and_reconnect(self):
        key = self.connection.connect(adb_serial="usb")["device_id"]
        self.features(key, "adb", False, False)
        self.assertTrue(self.call(key, "observe", mode="screenshot")["screenshot"]["available"])
        self.assertEqual(key, self.pair())
        self.features(key, "app+adb", True, True)
        self.assertEqual([key], [d["device_id"] for d in self.connection.list_devices()["devices"]])
        args = dict(request_id="edit", observation_id="app-snapshot", element_id="0.2", text="中文", mode="insert")
        self.assertTrue(self.call(key, "input_text", **args)["action_executed"])
        self.assertEqual("中文", self.app_calls[-1][1]["text"])
        audio_file = self.connection.store.path.parent / "voice.wav"
        with wave.open(str(audio_file), "wb") as audio:
            audio.setparams((1, 2, 8000, 0, "NONE", ""))
            audio.writeframes(b"\x00\x01" * 800)
        self.call(key, "send_voice", request_id="voice", observation_id="app-snapshot", x=1, y=2, audio_file=str(audio_file))
        self.assertEqual("send_voice", self.app_calls[-1][0])
        self.assertEqual(b"\x00\x01" * 800, base64.b64decode(self.app_calls[-1][1]["pcm_base64"]))
        self.usb = False
        self.features(key, "app", True, False)
        self.assertTrue(self.call(key, "observe", mode="screenshot")["screenshot"]["available"])
        self.assertEqual("VOICE_UNAVAILABLE", self.call(key, "send_voice", request_id="voice-off")["error"]["code"])
        self.usb = True
        self.audio_route = False
        self.features(key, "app+adb", True, False)
        self.connection.disconnect(key, "app")
        self.features(key, "adb", False, False)
        self.assertEqual("RESULT_UNKNOWN", self.call(key, "input_text", **args)["error"]["code"])
        self.assertEqual("ACTION_UNSUPPORTED", self.call(key, "input_text", **{**args, "request_id": "edit-off"})["error"]["code"])
        shot = self.call(key, "observe", mode="screenshot")
        self.assertEqual(self.frame, base64.b64decode(shot["screenshot"]["data_base64"]))
        self.assertEqual(key, self.connection.connect(device_id=key)["device_id"])
        self.features(key, "app+adb", True, False)

    def test_app_first_verifies_both_pin_and_id(self):
        key = self.pair()
        self.assertEqual(key, self.connection.connect_adb("usb", key)["device_id"])
        self.features(key, "app+adb", True, True)
        self.connection.disconnect(key, "adb")
        self.features(key, "app", True, False)
        self.app["tls_pin"] = "01" * 32
        with self.assertRaises(client.BridgeError) as error:
            self.connection.connect_adb("usb", key)
        self.assertEqual("TLS_IDENTITY_MISMATCH", error.exception.value["error"]["code"])
        self.app = identity("other")
        with self.assertRaises(client.BridgeError):
            self.connection.connect_adb("usb", key)

    def test_adb_actions_deduplicate_across_processes_and_reject_changed_frames(self):
        key = self.connection.connect_adb("usb")["device_id"]
        shot = self.call(key, "observe", mode="screenshot")
        args = dict(x=3, y=5, observation_id=shot["observation_id"], request_id="once")
        result = self.call(key, "tap", **args)
        self.assertTrue(result["action_executed"])
        self.connection = client.Connection(self.connection.store.path)
        self.assertEqual(result, self.call(key, "tap", **args))
        self.assertEqual([("tap", "3", "5")], self.inputs)
        self.frame += b"changed"
        stale = self.call(key, "tap", **{**args, "request_id": "again"})
        self.assertEqual("STALE_OBSERVATION", stale["error"]["code"])
        self.assertFalse(stale["action_executed"])
        self.assertEqual(result, self.call(key, "get_status", request_id="once")["request"])
        self.assertEqual(1, len(self.inputs))

    def test_adb_reports_lock_and_refuses_to_act_on_phoneuse_controls(self):
        key = self.connection.connect_adb("usb")["device_id"]
        with patch.object(adb.AdbDevice, "foreground", return_value=("window", "ai.nidhoggfgg.phoneuse")):
            shot = self.call(key, "observe", mode="screenshot")
            result = self.call(key, "tap", request_id="pairing", x=1, y=2, observation_id=shot["observation_id"])
        self.assertEqual("LOCAL_CONTROL_UI", result["error"]["code"])
        def locked(*args):
            if args[-3:] == ("dumpsys", "window", "policy"):
                return b" showing=true\n"
            return self.adb_command(*args)
        with patch.object(adb, "adb", side_effect=locked):
            result = self.call(key, "home", request_id="locked")
        self.assertEqual("DEVICE_LOCKED", result["error"]["code"])
        self.assertEqual([], self.inputs)

    def test_ambiguous_action_never_replayed_on_other_backend(self):
        key = self.connection.connect_adb("usb")["device_id"]
        cmd = self.adb_command
        def disconnect_after_input(*args):
            result = cmd(*args)
            if "input" in args:
                raise client.BridgeError("ADB_UNAVAILABLE", "lost reply")
            return result
        with patch.object(adb, "adb", side_effect=disconnect_after_input):
            unknown = self.call(key, "home", request_id="once")
        self.assertIsNone(unknown["action_executed"])
        self.assertEqual(key, self.pair())
        self.assertIsNone(self.call(key, "home", request_id="once")["action_executed"])
        self.assertFalse(any(tool == "home" for tool, _ in self.app_calls))
        self.assertEqual("unknown", self.call(key, "get_status", request_id="once")["request"]["state"])

    def test_adb_mcp_image_catalog_and_device_guard(self):
        key = self.connection.connect_adb("usb")["device_id"]
        catalog = self.connection.catalog()
        self.assertIn("input_text", [t["name"] for t in catalog])
        response = self.connection.send(key, "/mcp", {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
            "params": {"name": "observe", "arguments": {"device_id": key, "mode": "screenshot"}}})
        blocks = response["result"]["content"]
        self.assertEqual(key, json.loads(blocks[1]["text"])["device_id"])
        self.assertEqual(self.frame, base64.b64decode(blocks[0]["data"]))
        self.marker = str(uuid.uuid4())
        with self.assertRaises(client.BridgeError) as error:
            self.call(key, "home", request_id="wrong")
        self.assertEqual("DEVICE_IDENTITY_MISMATCH", error.exception.value["error"]["code"])
        self.assertFalse(self.inputs)

    def test_stopped_app_degrades_and_accessibility_loss_keeps_screenshots(self):
        key = self.connection.connect_adb("usb")["device_id"]
        self.pair()
        self.accessible = False
        self.features(key, "app+adb", False, False)
        self.assertTrue(self.call(key, "observe", mode="screenshot")["observation_id"].startswith("adb-"))
        self.installed = False
        self.features(key, "adb", False, False)
        self.assertTrue(self.call(key, "observe", mode="screenshot")["screenshot"]["available"])
        self.usb = False
        with self.assertRaises(client.BridgeError):
            self.call(key, "get_capabilities")

    def test_previously_separate_registrations_merge_only_after_proof(self):
        key = self.connection.connect_adb("usb")["device_id"]
        self.usb = False
        second = self.pair()
        self.assertNotEqual(key, second)
        self.usb = True
        self.assertEqual(key, self.connection.connect_adb("usb", second)["device_id"])
        self.assertEqual([key], list(self.connection.config["devices"]))
        self.features(key, "app+adb", True, True)

    def test_host_reservation_survives_adding_app(self):
        key = self.connection.connect_adb("usb")["device_id"]
        lease = self.call(key, "acquire_device", ttl_ms=30000)["lease_id"]
        self.pair()
        rejected = self.call(key, "home", request_id="other")
        self.assertEqual("DEVICE_RESERVED", rejected["error"]["code"])
        self.assertTrue(self.call(key, "home", request_id="owner", lease_id=lease)["action_executed"])
        self.assertNotIn("lease_id", self.app_calls[-1][1])
        self.assertEqual("released", self.call(key, "release_device", lease_id=lease)["state"])

    def test_app_network_loss_uses_pinned_forwarded_app_and_releases_port(self):
        key = self.connection.connect_adb("usb")["device_id"]
        self.pair()
        def identify(address, pin=None):
            if address.startswith("https://phone"):
                raise TimeoutError()
            self.assertEqual(self.app["tls_pin"], pin)
            return self.app.copy()
        with patch.object(client, "identify", side_effect=identify), patch.object(adb, "adb", wraps=self.adb_command) as transport:
            shot = self.call(key, "observe", mode="screenshot")
        self.assertEqual("app-snapshot", shot["observation_id"])
        transport.assert_any_call("-t", "42", "forward", "--remove", "tcp:31500")
