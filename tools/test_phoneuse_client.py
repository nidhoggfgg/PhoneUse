"""Run with python -m unittest discover -s tools -p 'test_*.py'."""
import base64
from concurrent.futures import ThreadPoolExecutor
import copy
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import wave
from unittest.mock import patch, MagicMock

import phoneuse_client as client
from tls_test_support import CERT_DER, PIN, wrap_server


def identity(device_id="phone-a", instance="run-a"):
    return {"device_id": device_id, "service_instance_id": instance, "name": "测试手机 " + device_id,
            "model": "Pixel", "android_version": "16", "is_emulator": False, "tls_pin": PIN}


def pending():
    return {"pairing_id": "pair-id", "pairing_secret": "pair-secret", "verification_code": "123456",
            "status": "committed", "protocol": client.PAIRING_PROTOCOL, "server_nonce": "22" * 32, "expires_in": 120, "poll_interval": 2, **identity()}


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "phoneuse.json"
        self.connection = client.Connection(self.path)

    def save_devices(self, *devices):
        saved = {}
        for name in devices or ("phone-a",):
            suffix = name.rsplit("-", 1)[-1]
            saved[name] = {**identity(name, "run-" + suffix), "url": f"https://phone-{suffix}:8443",
                           "token": "secret-" + suffix, "client_id": "client-" + suffix}
        self.path.write_text(json.dumps({"version": 2, "devices": saved}), encoding="utf-8")

    @staticmethod
    def identify_address(address, tls_pin=None):
        name = address.removeprefix("https://").split(":")[0]
        return identity(name, "run-" + name.rsplit("-", 1)[-1])

    def read(self):
        return json.loads(self.path.read_text(encoding="utf-8"))

    def test_voice_file_is_read_on_bridge_for_api_and_mcp(self):
        path = self.path.parent / "语音.wav"
        samples = b"\x00\x01" * 8000
        with wave.open(str(path), "wb") as wav:
            wav.setparams((1, 2, 8000, 0, "NONE", ""))
            wav.writeframes(samples)
        self.save_devices()
        for endpoint in ("/api", "/mcp"):
            args = {"audio_file": str(path), "request_id": "a", "element_id": "0", "observation_id": "snapshot"}
            payload = ({"tool": "send_voice", "arguments": args} if endpoint == "/api" else
                       {"id": "a", "method": "tools/call", "params": {"name": "send_voice", "arguments": args}})
            response = identity() if endpoint == "/api" else {"id": "a", "jsonrpc": "2.0", "result": client.tool_result(identity())}
            with patch.object(client, "identify", self.identify_address), patch.object(client, "post", return_value=response) as post:
                self.connection.send("phone-a", endpoint, payload)
                sent = post.call_args.args[2]
                wire = (sent if endpoint == "/api" else sent["params"])["arguments"]
                self.assertNotIn("audio_file", wire)
                self.assertEqual(8000, wire["sample_rate"])
                self.assertEqual(samples, base64.b64decode(wire["pcm_base64"]))
                self.assertIn("audio_file", args)
        path.write_bytes(path.read_bytes()[:-2])
        with self.assertRaises(client.BridgeError) as failure:
            client.prepare_voice_file({"audio_file": str(path)}, "phone-a")
        self.assertFalse(failure.exception.value["action_executed"])

    def run_bridge(self, requests):
        incoming = io.StringIO("\n".join(json.dumps({"jsonrpc": "2.0", **item}) for item in requests))
        outgoing = io.StringIO()
        with patch("sys.stdin", incoming), patch("sys.stdout", outgoing):
            client.bridge(self.connection)
        return [json.loads(line) for line in outgoing.getvalue().splitlines()]

    def test_http_202_body_and_empty_notification(self):
        connection = MagicMock()
        connection.sock.getpeercert.return_value = CERT_DER
        response = connection.getresponse.return_value
        response.status = 202
        response.read.return_value = b'{"status":"pending"}'
        with patch.object(client.http.client, "HTTPSConnection", return_value=connection):
            self.assertEqual({"status": "pending"}, client.post({"url": "https://phone-a:8443", "tls_pin": PIN}, "/pair/request", {}, False))
            response.read.return_value = b""
            self.assertIsNone(client.post({"url": "https://phone-a:8443", "tls_pin": PIN}, "/mcp", {}, False))

    def test_real_http_checks_identity_and_routes_unicode_without_sending_token_to_reassigned_address(self):
        self.save_devices()
        served, seen = [identity()], []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, value):
                body = json.dumps(value, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                seen.append(("GET", self.path, self.headers.get("Authorization"), None))
                self.reply(served[0])

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append(("POST", self.path, self.headers.get("Authorization"), payload))
                self.reply({"state": "executed", **served[0]})

        server = wrap_server(ThreadingHTTPServer(("127.0.0.1", 0), Handler))
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        try:
            self.connection._update("phone-a", url=f"https://127.0.0.1:{server.server_port}")
            result = self.connection.send("phone-a", "/api", {"tool": "input_text", "arguments": {"text": "中文", "request_id": "one"}})
            self.assertEqual("phone-a", result["device_id"])
            self.assertEqual(("GET", "/identity", None), seen[0][:3])
            self.assertEqual(("POST", "/api", "Bearer secret-a"), seen[1][:3])
            self.assertEqual("中文", seen[1][3]["arguments"]["text"])
            self.assertEqual("phone-a", seen[1][3]["arguments"]["device_id"])
            served[0] = identity("other-install", "other-run")
            with self.assertRaises(client.BridgeError):
                self.connection.send("phone-a", "/api", {"tool": "home", "arguments": {"request_id": "two"}})
            self.assertEqual(1, sum(item[0] == "POST" for item in seen))
            self.assertIsNone(seen[-1][2])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_identity_is_get_without_credentials_and_post_is_utf8(self):
        connection = MagicMock()
        connection.sock.getpeercert.return_value = CERT_DER
        response = connection.getresponse.return_value
        response.status = 200
        response.read.return_value = json.dumps(identity()).encode()
        with patch.object(client.http.client, "HTTPSConnection", return_value=connection):
            client.identify("phone-a")
            args = connection.request.call_args.args
            self.assertEqual(("GET", "/identity", None), args[:3])
            self.assertNotIn("Authorization", args[3])
            client.post({"url": "phone-a", "token": "secret", "tls_pin": PIN}, "/api", {"text": "中文"})
            self.assertIn("中文".encode("utf-8"), connection.request.call_args.args[2])
            self.assertEqual("Bearer secret", connection.request.call_args.args[3]["Authorization"])

    def test_origins_accept_address_hints_but_reject_path_or_credentials(self):
        self.assertEqual("https://192.168.1.2:8443", client.origin("192.168.1.2"))
        self.assertEqual("https://[::1]:8443", client.origin("https://[::1]"))
        for value in ("http://phone", "https://u:p@phone", "https://phone/api", "https://phone?token=x"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                client.origin(value)

    def test_pairing_survives_restart_and_never_exposes_secrets(self):
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=[
                pending(), {"status": "pending", **identity()}, {"status": "approved", "token": "new-secret", "client_id": "client", **identity()}]) as post:
            first = self.connection.connect("phone-a")
            second = client.Connection(self.path).connect(device_id="phone-a", confirm_pairing=True)
        self.assertEqual("pending", first["status"])
        self.assertEqual("paired", second["status"])
        self.assertEqual("phone-a", second["device_id"])
        self.assertNotIn("pair-secret", json.dumps(first))
        self.assertNotIn("new-secret", json.dumps(second))
        self.assertNotIn("pending", self.read()["devices"]["phone-a"])
        self.assertEqual("new-secret", self.read()["devices"]["phone-a"]["token"])
        self.assertEqual(["/pair/request", "/pair/reveal", "/pair/status"], [call.args[1] for call in post.call_args_list])

    def test_pairing_denial_clears_pending_without_credential(self):
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=[pending(), {"status": "pending", **identity()}, {"status": "denied", **identity()}]):
            self.connection.connect("phone-a")
            with self.assertRaisesRegex(client.BridgeError, "denied"):
                self.connection.connect(device_id="phone-a", confirm_pairing=True)
        self.assertNotIn("pending", self.read()["devices"]["phone-a"])
        self.assertNotIn("token", self.read()["devices"]["phone-a"])

    def test_expired_pairing_and_restarted_service_never_send_pending_secret(self):
        for expire_by_time in (True, False):
            with self.subTest(expire_by_time=expire_by_time):
                self.path.unlink(missing_ok=True)
                with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=lambda config, endpoint, *a, **kw: pending() if endpoint == "/pair/request" else {"status": "pending", **identity()}):
                    self.connection.connect("phone-a")
                if expire_by_time:
                    with self.connection.store.transaction(write=True) as config:
                        config["devices"]["phone-a"]["pending"]["deadline"] = 0
                new_identity = identity(instance="run-a" if expire_by_time else "restarted")
                with patch.object(client, "identify", return_value=new_identity), patch.object(client, "post") as post:
                    with self.assertRaisesRegex(client.BridgeError, "expired|restarted"):
                        self.connection.connect(device_id="phone-a", confirm_pairing=True)
                    post.assert_not_called()
                self.assertNotIn("pending", self.read()["devices"]["phone-a"])

    def test_reconnect_at_new_address_verifies_identity_before_reusing_token(self):
        self.save_devices()
        with patch.object(client, "identify", return_value=identity()) as identify_mock, patch.object(client, "post", return_value=identity()) as post:
            result = self.connection.connect("192.168.1.25", "phone-a")
        self.assertEqual("paired", result["status"])
        self.assertEqual("https://192.168.1.25:8443", self.read()["devices"]["phone-a"]["url"])
        self.assertEqual("secret-a", post.call_args.args[0]["token"])
        self.assertGreaterEqual(identify_mock.call_count, 1)

    def test_wrong_phone_at_saved_address_receives_no_token(self):
        self.save_devices()
        with patch.object(client, "identify", return_value=identity("new-install")), patch.object(client, "post") as post:
            with self.assertRaises(client.BridgeError) as raised:
                self.connection.send("phone-a", "/api", {"tool": "home", "arguments": {"request_id": "one"}})
            self.assertEqual("DEVICE_IDENTITY_MISMATCH", raised.exception.value["error"]["code"])
            self.assertFalse(raised.exception.value["action_executed"])
            with self.assertRaises(client.BridgeError):
                self.connection.connect(device_id="phone-a", confirm_pairing=True)
            post.assert_not_called()
        self.assertEqual("secret-a", self.read()["devices"]["phone-a"]["token"])

    def test_connecting_reassigned_address_registers_new_identity_without_old_token(self):
        self.save_devices()
        new_identity = identity("new-install", "run-new")
        with patch.object(client, "identify", return_value=new_identity), patch.object(client, "post", side_effect=lambda config, endpoint, *a, **kw: {**pending(), **new_identity} if endpoint == "/pair/request" else {"status": "pending", **new_identity}) as post:
            result = self.connection.connect("phone-a")
        self.assertEqual("new-install", result["device_id"])
        self.assertNotIn("token", post.call_args.args[0])
        self.assertEqual({"phone-a", "new-install"}, set(self.read()["devices"]))

    def test_unbound_legacy_credentials_require_new_phone_approval(self):
        self.path.write_text(json.dumps({"url": "https://phone-a:8443", "token": "legacy-secret"}))
        self.assertIn("Legacy", self.connection.list_devices(False)["message"])
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=[pending(), {"status": "pending", **identity()},
                {"status": "approved", "token": "fresh-secret", "client_id": "fresh", **identity()}]) as post:
            self.connection.connect()
            self.connection.connect(device_id="phone-a", confirm_pairing=True)
        for call in post.call_args_list:
            self.assertNotIn("legacy-secret", str(call))
        self.assertNotIn("legacy_pairing_requires_approval", self.read())
        self.assertEqual("fresh-secret", self.read()["devices"]["phone-a"]["token"])

    def test_bound_single_device_http_config_requires_fresh_approval(self):
        self.path.write_text(json.dumps({"url": "http://phone-a:8443", "token": "bound-secret", "device_id": "phone-a"}))
        self.assertNotIn("token", self.connection.config["devices"]["phone-a"])
        self.assertEqual("https://phone-a:8443", self.connection.config["devices"]["phone-a"]["url"])

    def test_revocation_is_isolated_and_does_not_implicitly_pair(self):
        self.save_devices("phone-a", "phone-b")
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=client.HttpError(401, {"error": "revoked"})) as post:
            with self.assertRaisesRegex(client.BridgeError, "revoked"):
                self.connection.send("phone-a", "/api", {"tool": "home"})
        self.assertEqual(1, post.call_count)
        self.assertNotIn("token", self.read()["devices"]["phone-a"])
        self.assertEqual("secret-b", self.read()["devices"]["phone-b"]["token"])

    def test_offline_identity_sends_nothing_and_retains_pairing(self):
        self.save_devices()
        with patch.object(client, "identify", side_effect=ConnectionRefusedError("offline")), patch.object(client, "post") as post:
            with self.assertRaises(client.BridgeError) as raised:
                self.connection.send("phone-a", "/api", {"tool": "home"})
            self.assertFalse(raised.exception.value["action_executed"])
            post.assert_not_called()
        self.assertEqual("secret-a", self.read()["devices"]["phone-a"]["token"])

    def test_network_failure_after_send_does_not_retry_action(self):
        self.save_devices()
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=ConnectionResetError("uncertain")) as post:
            with self.assertRaises(ConnectionResetError):
                self.connection.send("phone-a", "/api", {"tool": "home", "arguments": {"request_id": "one"}})
            self.assertEqual(1, post.call_count)
        self.assertEqual("secret-a", self.read()["devices"]["phone-a"]["token"])

    def test_api_routing_and_instance_are_explicit_and_input_is_not_mutated(self):
        self.save_devices()
        payload = {"tool": "tap", "arguments": {"request_id": "one", "lease_id": "reservation"}}
        original = copy.deepcopy(payload)
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value={"state": "executed", **identity()}) as post:
            result = self.connection.send("phone-a", "/api", payload)
        self.assertEqual(original, payload)
        self.assertEqual({"request_id": "one", "lease_id": "reservation", "device_id": "phone-a", "service_instance_id": "run-a"}, post.call_args.args[2]["arguments"])
        self.assertEqual("phone-a", result["device_id"])
        self.assertEqual("run-a", result["service_instance_id"])

    def test_nested_route_conflict_is_rejected_before_action(self):
        self.save_devices()
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post") as post:
            with self.assertRaises(client.BridgeError):
                self.connection.send("phone-a", "/api", {"tool": "tap", "arguments": {"device_id": "phone-b"}})
            post.assert_not_called()

    def test_wrong_identity_or_instance_in_response_is_not_reported_as_success(self):
        self.save_devices()
        for response in ({**identity(), "device_id": "other"}, {**identity(), "service_instance_id": "restarted"}):
            with self.subTest(response=response), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response):
                with self.assertRaises(client.BridgeError) as raised:
                    self.connection.send("phone-a", "/api", {"tool": "home"})
                self.assertIsNone(raised.exception.value["action_executed"])

    def test_list_devices_reports_metadata_offline_status_without_secrets(self):
        self.save_devices("phone-a", "phone-b")
        def identify_some(address, tls_pin=None):
            if "phone-b" in address:
                raise ConnectionRefusedError("offline")
            return identity()
        with patch.object(client, "identify", side_effect=identify_some), patch.object(client, "post") as post:
            result = self.connection.list_devices()
        self.assertEqual([True, False], [item["online"] for item in result["devices"]])
        self.assertNotIn("secret", json.dumps(result))
        self.assertNotIn("client_id", json.dumps(result))
        post.assert_not_called()

    def test_same_device_serializes_across_registry_instances_while_other_device_runs(self):
        self.save_devices("phone-a", "phone-b")
        another = client.Connection(self.path)
        started, release, second_entered, other_entered = (threading.Event() for _ in range(4))
        def send(config, endpoint, payload):
            name = payload["tool"]
            if name == "first":
                started.set()
                self.assertTrue(release.wait(3))
            elif name == "second":
                second_entered.set()
            else:
                other_entered.set()
            return {"state": "executed", **self.identify_address(config["url"])}
        with patch.object(client, "identify", side_effect=self.identify_address), patch.object(client, "post", side_effect=send):
            with ThreadPoolExecutor(max_workers=3) as pool:
                first = pool.submit(self.connection.send, "phone-a", "/api", {"tool": "first"})
                self.assertTrue(started.wait(2))
                second = pool.submit(another.send, "phone-a", "/api", {"tool": "second"})
                other = pool.submit(self.connection.send, "phone-b", "/api", {"tool": "other"})
                try:
                    self.assertTrue(other_entered.wait(2), "Device B must not wait for device A")
                    self.assertFalse(second_entered.is_set(), "Same-device calls must not overlap")
                finally:
                    release.set()
                for future in (first, second, other):
                    future.result(3)
        self.assertTrue(second_entered.is_set())
        self.assertEqual({"phone-a", "phone-b"}, set(self.read()["devices"]))

    def test_catalog_requires_route_and_preserves_tool_arguments(self):
        self.save_devices("phone-a", "phone-b")
        remote = {"jsonrpc": "2.0", "id": "catalog", "result": {"tools": [{"name": "observe", "inputSchema": {"type": "object", "properties": {"compact": {"type": "boolean"}}, "additionalProperties": False}}]}}
        with patch.object(client, "identify", side_effect=self.identify_address), patch.object(client, "post", return_value=remote):
            tools = self.connection.catalog()
        self.assertEqual(1, len(tools))
        self.assertEqual(["device_id"], tools[0]["inputSchema"]["required"])
        self.assertIn("compact", tools[0]["inputSchema"]["properties"])
        self.assertNotIn("device_id", remote["result"]["tools"][0]["inputSchema"]["properties"])

    def test_mcp_initializes_offline_and_missing_route_never_sends_action(self):
        self.save_devices()
        requests = [{"id": 1, "method": "initialize"}, {"id": 2, "method": "tools/list"},
                    {"method": "tools/call", "params": {"name": "home", "arguments": {"device_id": "phone-a"}}},
                    {"id": 3, "method": "tools/call", "params": {"name": "home", "arguments": {"request_id": "one"}}}]
        with patch.object(client, "identify", side_effect=ConnectionRefusedError("offline")), patch.object(client, "post") as post:
            messages = {item["id"]: item for item in self.run_bridge(requests)}
        self.assertIn("capabilities", messages[1]["result"])
        self.assertEqual("phoneuse_connect", messages[2]["result"]["tools"][0]["name"])
        error = json.loads(messages[3]["result"]["content"][0]["text"])
        self.assertEqual("DEVICE_REQUIRED", error["error"]["code"])
        post.assert_not_called()

    def test_mcp_pending_device_does_not_block_other_device_and_keeps_per_device_order(self):
        self.save_devices("phone-a", "phone-b")
        device_b_ran = threading.Event()
        order = []
        def send(config, endpoint, payload):
            request_id = payload["params"]["arguments"]["request_id"]
            if request_id == "a1":
                self.assertTrue(device_b_ran.wait(3), "Device B must be serviced while A waits")
            if request_id == "b1":
                device_b_ran.set()
            order.append(request_id)
            return {"jsonrpc": "2.0", "id": payload["id"], "result": client.tool_result({"state": "executed", **self.identify_address(config["url"])})}
        requests = [{"id": index, "method": "tools/call", "params": {"name": "phoneuse_call", "arguments": {
            "device_id": device, "tool": "home", "arguments": {"request_id": request}}}}
            for index, (device, request) in enumerate((("phone-a", "a1"), ("phone-a", "a2"), ("phone-b", "b1")))]
        with patch.object(client, "identify", side_effect=self.identify_address), patch.object(client, "post", side_effect=send):
            results = self.run_bridge(requests)
        self.assertEqual(["b1", "a1", "a2"], order)
        for response in results:
            value = json.loads(response["result"]["content"][0]["text"])
            self.assertEqual("phone-b" if response["id"] == 2 else "phone-a", value["device_id"])

    def test_mcp_protocol_error_is_preserved_as_unexecuted_with_device_identity(self):
        self.save_devices()
        request = {"jsonrpc": "2.0", "id": 42, "method": "tools/call", "params": {"name": "unknown_tool"}}
        response = {"jsonrpc": "2.0", "id": 42, "error": {"code": -32602, "message": "Unknown tool"}}
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response):
            result = self.connection.send("phone-a", "/mcp", request)
        self.assertEqual(-32602, result["error"]["code"])
        self.assertEqual("Unknown tool", result["error"]["message"])
        self.assertEqual("phone-a", result["error"]["data"]["device_id"])
        self.assertFalse(result["error"]["data"]["action_executed"])

    def test_mcp_mismatched_response_id_is_not_accepted(self):
        self.save_devices()
        request = {"jsonrpc": "2.0", "id": 42, "method": "tools/call", "params": {"name": "home"}}
        response = {"jsonrpc": "2.0", "id": 43, "result": client.tool_result({"state": "executed", **identity()})}
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response):
            with self.assertRaises(client.BridgeError) as raised:
                self.connection.send("phone-a", "/mcp", request)
        self.assertEqual("INVALID_REMOTE_RESPONSE", raised.exception.value["error"]["code"])
        self.assertIsNone(raised.exception.value["action_executed"])

    def test_batch_aggregates_action_and_observation_errors_separately_and_skips_dependencies(self):
        self.save_devices("phone-a", "phone-b")
        calls = [{"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "a1"}, "lease_id": "lease-a"},
                 {"device_id": "phone-b", "tool": "home", "arguments": {"request_id": "b1"}},
                 {"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "a2"}}]
        def send(config, endpoint, payload):
            if "phone-a" in config["url"]:
                self.assertEqual("lease-a", payload["arguments"]["lease_id"])
                return {"state": "executed", "observation": {"error": {"code": "OBSERVATION_INCONSISTENT"}}, **identity()}
            return {"state": "failed", "error": {"code": "STALE_OBSERVATION", "reason": "expired"}, **identity("phone-b", "run-b")}
        with patch.object(client, "identify", side_effect=self.identify_address), patch.object(client, "post", side_effect=send) as post:
            value = self.connection.batch(calls)
        self.assertEqual(2, post.call_count)
        self.assertEqual([0, 1, 2], [item["index"] for item in value["results"]])
        self.assertEqual(1, value["summary"]["succeeded"])
        self.assertEqual(1, value["summary"]["failed"])
        self.assertEqual(1, value["summary"]["skipped"])
        self.assertEqual({"OBSERVATION_INCONSISTENT": 1}, value["summary"]["observation_error_codes"])
        self.assertEqual(1, value["summary"]["error_codes"]["STALE_OBSERVATION"])

    def test_batch_pending_and_unknown_are_not_counted_as_success(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "home", "arguments": {"request_id": str(index)}} for index in range(3)]
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=[{"state": state, **identity()} for state in ("accepted", "unknown", "executed")]):
            value = self.connection.batch(calls, summary_only=True, continue_on_error=True)
        self.assertNotIn("results", value)
        self.assertEqual((1, 1, 1), tuple(value["summary"][key] for key in ("pending", "unknown", "succeeded")))

    def test_batch_lost_response_and_missing_identity_are_unknown_and_never_retried(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "home", "arguments": {"request_id": str(index)}} for index in range(2)]
        for response in (ConnectionResetError("response lost"), {"state": "executed"}):
            behavior = {"side_effect": response} if isinstance(response, Exception) else {"return_value": response}
            with self.subTest(response=response), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", **behavior) as post:
                value = self.connection.batch(calls)
            self.assertEqual(1, post.call_count)
            self.assertEqual(1, value["summary"]["unknown"])
            self.assertEqual(0, value["summary"]["failed"])
            self.assertEqual(1, value["summary"]["skipped"])
            self.assertIsNone(value["results"][0]["result"]["action_executed"])

    def test_batch_status_poll_uses_nested_request_state_and_stops_dependent_steps(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "get_status", "arguments": {"request_id": "one"}},
                 {"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "two"}}]
        for state, summary_key in (("executing", "pending"), ("unknown", "unknown"), ("failed", "failed")):
            response = {"request": {"state": state, "request_id": "one"}, **identity()}
            with self.subTest(state=state), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response) as post:
                value = self.connection.batch(calls)
            self.assertEqual(1, post.call_count)
            self.assertEqual(1, value["summary"][summary_key])
            self.assertEqual(1, value["summary"]["skipped"])
            self.assertEqual(0, value["summary"]["succeeded"])
            self.assertEqual(state, value["results"][0]["result"]["request"]["state"])

    def test_cli_saves_screenshot_and_prints_utf8_compact_tree_without_base64(self):
        self.save_devices()
        output = io.StringIO()
        screenshot_path = Path(self.directory.name) / "截图.jpg"
        args = ["phoneuse_client.py", "--config", str(self.path), "call", "observe", "--device-id", "phone-a", "--full-tree", "--screenshot-out", str(screenshot_path)]
        result = {"text": "中文", "screenshot": {"data_base64": base64.b64encode(b"jpeg bytes").decode(), "available": True}, **identity()}
        with patch("sys.argv", args), patch("sys.stdout", output), patch.object(client, "utf8_stdio"), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=result) as post:
            client.main()
        self.assertFalse(post.call_args.args[2]["arguments"]["compact"])
        self.assertEqual("legacy", post.call_args.args[2]["arguments"]["output"])
        self.assertEqual(b"jpeg bytes", screenshot_path.read_bytes())
        self.assertIn("中文", output.getvalue())
        value = json.loads(output.getvalue())
        self.assertEqual("phone-a", value["device_id"])
        self.assertNotIn("data_base64", value["screenshot"])
        self.assertTrue(value["screenshot"]["image_data_omitted"])
        self.assertEqual(str(screenshot_path.resolve()), value["screenshot"]["saved_path"])

    def test_conditional_observation_blocks_dependencies_without_replaying_executed_action(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "launch_app", "arguments": {"request_id": "launch", "observe_after": True}},
                 {"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "next"}}]
        for condition in ("unsatisfied", "ambiguous", "unknown"):
            observation = {"observation_status": "condition_timeout", "condition": {"status": condition, "timed_out": True}}
            response = {"state": "executed", "action_executed": True, "observation": observation, **identity()}
            with self.subTest(condition=condition), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response) as post:
                value = self.connection.batch(calls)
            self.assertEqual(1, post.call_count)
            self.assertTrue(value["results"][0]["result"]["action_executed"])
            self.assertEqual(1, value["summary"]["succeeded"])
            self.assertEqual(1, value["summary"]["skipped"])
            self.assertEqual(1, value["summary"]["observation_incomplete"])
            self.assertEqual({condition: 1}, value["summary"]["condition_statuses"])

    def test_failed_latest_observation_does_not_use_historical_condition_as_readiness(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "get_status", "arguments": {"request_id": "one"}},
                 {"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "two"}}]
        response = {"request": {"state": "executed", "action_executed": True, "observation_status": "failed", "observation": {
            "observation_status": "failed", "condition": {"status": "unknown", "timed_out": False},
            "error": {"code": "OBSERVATION_INCONSISTENT"}, "last_observation": {
                "condition": {"status": "satisfied", "timed_out": False}, "captured_at_ms": 123}}}, **identity()}
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response) as post:
            value = self.connection.batch(calls)
        self.assertEqual(1, post.call_count)
        self.assertEqual({"unknown": 1}, value["summary"]["condition_statuses"])
        self.assertEqual({"failed": 1}, value["summary"]["observation_statuses"])
        self.assertEqual({"OBSERVATION_INCONSISTENT": 1}, value["summary"]["observation_error_codes"])
        self.assertEqual(1, value["summary"]["observation_incomplete"])

    def test_accepted_false_and_executed_observing_are_still_pending_without_retries(self):
        self.save_devices()
        calls = [{"device_id": "phone-a", "tool": "get_status", "arguments": {"request_id": "one"}},
                 {"device_id": "phone-a", "tool": "home", "arguments": {"request_id": "two"}}]
        for request in ({"state": "accepted", "action_executed": False},
                        {"state": "executing", "action_executed": True, "observation_status": "observing"}):
            with self.subTest(request=request), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value={"request": request, **identity()}) as post:
                value = self.connection.batch(calls)
            self.assertEqual(1, post.call_count)
            self.assertEqual(1, value["summary"]["pending"])
            self.assertEqual(1, value["summary"]["skipped"])
            self.assertEqual(0, value["summary"]["succeeded"])

    def test_rejection_keeps_original_error_and_counts_failed_recovery_observation(self):
        self.save_devices()
        response = {"state": "failed", "action_executed": False, "error": {"code": "STALE_ELEMENT"},
                    "recovery": {"category": "refresh_observation", "advisory": True, "replay_action": False},
                    "recovery_observation": {"observation_status": "failed", "error": {"code": "PAUSED"}}, **identity()}
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response) as post:
            value = self.connection.batch([{"device_id": "phone-a", "tool": "tap", "arguments": {"request_id": "one"}}])
        self.assertEqual(1, post.call_count)
        self.assertEqual({"STALE_ELEMENT": 1}, value["summary"]["error_codes"])
        self.assertEqual({"PAUSED": 1}, value["summary"]["observation_error_codes"])
        self.assertEqual(response["recovery"], value["results"][0]["result"]["recovery"])

    def test_standalone_conditions_and_requested_unavailable_parts_are_incomplete(self):
        incomplete = [
            {"condition": {"status": "unsatisfied", "timed_out": True}},
            {"condition": {"status": "satisfied", "timed_out": True}},
            {"observation_status": "complete", "tree": {"available": True}, "screenshot": {"available": False}},
            {"observation_status": "complete", "tree": {"available": False}, "screenshot": None},
        ]
        for value in incomplete:
            with self.subTest(value=value):
                self.assertTrue(client.observation_incomplete(value))
        self.assertFalse(client.observation_incomplete({"condition": {"status": "satisfied", "timed_out": False}, "screenshot": None}))
        self.assertTrue(client.observation_incomplete({"state": "executed"}, {"observe_after": True}))
        self.assertFalse(client.observation_incomplete({"state": "executed", "action_executed": True}))
        with patch.object(self.connection, "send", return_value={"observation_status": "failed", "error": {"code": "OBSERVATION_TIMEOUT"}}):
            value = self.connection.batch([{"device_id": "phone-a", "tool": "observe"}])
        self.assertEqual({"OBSERVATION_TIMEOUT": 1}, value["summary"]["observation_error_codes"])
        self.assertEqual(1, value["summary"]["failed"])

    def test_batch_mcp_marks_unmet_condition_as_error_but_continue_is_explicit(self):
        self.save_devices()
        response = {"state": "executed", "observation": {"condition": {"status": "unsatisfied", "timed_out": True}}, **identity()}
        calls = [{"device_id": "phone-a", "tool": "home", "arguments": {"request_id": str(index)}} for index in range(2)]
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response) as post:
            value = self.run_bridge([{"id": 1, "method": "tools/call", "params": {
                "name": "phoneuse_batch", "arguments": {"calls": calls, "continue_on_error": True}}}])[0]
        self.assertEqual(2, post.call_count)
        self.assertTrue(value["result"]["isError"])
        self.assertEqual(2, json.loads(value["result"]["content"][0]["text"])["summary"]["observation_incomplete"])

    def test_screenshot_extracts_nested_recovery_and_historical_observations_without_changing_facts(self):
        def shot(data):
            return {"available": True, "data_base64": base64.b64encode(data).decode()}
        value = {"request": {"state": "failed", "action_executed": False, "error": {"code": "STALE_ELEMENT"},
                            "recovery_observation": {"observation_status": "failed", "condition": {"status": "unknown"},
                                "last_observation": {"captured_at_ms": 200, "screenshot": shot(b"latest")}},
                            "last_observation": {"captured_at_ms": 100, "screenshot": shot(b"older")}}}
        path = Path(self.directory.name) / "nested.jpg"
        client.save_screenshot(value, path)
        self.assertEqual(b"latest", path.read_bytes())
        self.assertEqual("request.recovery_observation.last_observation.screenshot", value["screenshot_output_source"])
        self.assertEqual("failed", value["request"]["state"])
        self.assertEqual("unknown", value["request"]["recovery_observation"]["condition"]["status"])
        self.assertNotIn("data_base64", json.dumps(client.without_image_data(value)))

    def test_nested_observation_identity_conflicts_are_rejected_for_api_and_mcp(self):
        self.save_devices()
        for endpoint in ("/api", "/mcp"):
            result = {"state": "failed", "recovery_observation": {"last_observation": {**identity("other", "run-a")}}, **identity()}
            payload = {"tool": "tap", "arguments": {"request_id": "one"}}
            if endpoint == "/mcp":
                result = {"jsonrpc": "2.0", "id": 1, "result": client.tool_result(result)}
                payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "tap", "arguments": {"request_id": "one"}}}
            with self.subTest(endpoint=endpoint), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=result):
                with self.assertRaises(client.BridgeError) as raised:
                    self.connection.send("phone-a", endpoint, payload)
            self.assertEqual("DEVICE_IDENTITY_MISMATCH", raised.exception.value["error"]["code"])

    def test_standalone_conditional_read_has_longer_http_timeout_but_actions_do_not(self):
        connection = MagicMock()
        connection.sock.getpeercert.return_value = CERT_DER
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read.return_value = b'{}'
        for endpoint, payload, timeout in (("/api", {"tool": "observe"}, 90),
                                           ("/mcp", {"params": {"name": "observe"}}, 90),
                                           ("/api", {"tool": "home", "arguments": {"observe_after": True}}, 25)):
            with self.subTest(endpoint=endpoint, payload=payload), patch.object(client.http.client, "HTTPSConnection", return_value=connection) as factory:
                client.post({"url": "phone-a", "token": "secret", "tls_pin": PIN}, endpoint, payload)
            self.assertEqual(timeout, factory.call_args.kwargs["timeout"])

    def test_screenshot_save_failure_preserves_executed_action(self):
        value = {"state": "executed", "observation": {"error": {"code": "OBSERVATION_INCONSISTENT"}}}
        client.save_screenshot(value, Path(self.directory.name) / "missing.jpg")
        self.assertEqual("executed", value["state"])
        self.assertIn("screenshot_output_error", value)
        self.assertEqual("OBSERVATION_INCONSISTENT", value["observation"]["error"]["code"])

    def test_missing_identity_in_pairing_response_is_not_saved(self):
        with patch.object(client, "identify", return_value=identity()), patch.object(client, "post", side_effect=lambda config, endpoint, *a, **kw: pending() if endpoint == "/pair/request" else {"status": "pending", **identity()}):
            self.connection.connect("phone-a")
        for missing in ("device_id", "service_instance_id"):
            approved = {"status": "approved", "token": "unbound-secret", "client_id": "client", **identity()}
            approved.pop(missing)
            with self.subTest(missing=missing), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=approved):
                with self.assertRaises(client.BridgeError) as raised:
                    self.connection.connect(device_id="phone-a", confirm_pairing=True)
                self.assertEqual("IDENTITY_UNAVAILABLE", raised.exception.value["error"]["code"])
                self.assertNotIn("token", self.read()["devices"]["phone-a"])

    def test_missing_identity_in_api_and_mcp_results_is_rejected(self):
        self.save_devices()
        for endpoint in ("/api", "/mcp"):
            for missing in ("device_id", "service_instance_id"):
                response = {"state": "executed", **identity()}
                response.pop(missing)
                if endpoint == "/mcp":
                    response = {"jsonrpc": "2.0", "id": 1, "result": client.tool_result(response)}
                    payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "home", "arguments": {"request_id": "one"}}}
                else:
                    payload = {"tool": "home", "arguments": {"request_id": "one"}}
                with self.subTest(endpoint=endpoint, missing=missing), patch.object(client, "identify", return_value=identity()), patch.object(client, "post", return_value=response):
                    with self.assertRaises(client.BridgeError) as raised:
                        self.connection.send("phone-a", endpoint, payload)
                    self.assertEqual("IDENTITY_UNAVAILABLE", raised.exception.value["error"]["code"])
                    self.assertIsNone(raised.exception.value["action_executed"])

    def test_stdio_utf8_is_configured_explicitly(self):
        streams = [MagicMock() for _ in range(3)]
        with patch("sys.stdin", streams[0]), patch("sys.stdout", streams[1]), patch("sys.stderr", streams[2]):
            client.utf8_stdio()
        for stream in streams:
            stream.reconfigure.assert_called_once_with(encoding="utf-8", errors="strict")

    def test_cli_subprocess_overrides_ascii_environment_with_utf8(self):
        self.save_devices()
        completed = subprocess.run([sys.executable, str(Path(client.__file__).resolve()), "--config", str(self.path), "list-devices", "--cached"],
                                   env={**os.environ, "PYTHONIOENCODING": "ascii"}, capture_output=True, timeout=10, check=True)
        value = json.loads(completed.stdout.decode("utf-8"))
        self.assertEqual("测试手机 phone-a", value["devices"][0]["name"])
        self.assertNotIn(b"secret-a", completed.stdout)


if __name__ == "__main__":
    unittest.main()
