"""Security regressions use actual TLS sockets as well as adversarial fake responses."""
import copy
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch, MagicMock

import phoneuse_client as client
from tls_test_support import CERT_DER, PIN, wrap_server


class TlsPairingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.connection = client.Connection(Path(self.directory.name) / "registry.json")
        self.identity = {"device_id": "phone", "service_instance_id": "run", "tls_pin": PIN}

    def test_spki_parser_matches_independent_openssl_extraction(self):
        self.assertEqual(PIN, client.public_key_pin(CERT_DER))
        for value in (b"", b"0\x80", CERT_DER[:-1], b"0\x84\xff\xff\xff\xff"):
            with self.assertRaises((ValueError, IndexError)):
                client.public_key_pin(value)

    def test_shared_cross_language_transcript_vector(self):
        self.assertEqual("9752824c0f5f6e1fb8abbea0ef2960d94e93db4125093766cdcb87bdf8b589e1", client.pairing_digest("commit", "00" * 32, "11" * 32).hex())
        self.assertEqual("17242944", client.pairing_code("00" * 32, "11" * 32, "22" * 32))

    def test_tls_pin_checked_before_any_http_or_bearer_bytes(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def handle(self):
                try:
                    super().handle()
                except ConnectionResetError:
                    pass  # A rejected pin closes the socket before sending HTTP.

            def log_message(self, *args):
                pass
            def do_GET(self):
                seen.append((self.path, self.headers.get("Authorization")))
                body = b'{"device_id":"phone","service_instance_id":"run","_tls_pin":"forged"}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        server = wrap_server(HTTPServer(("127.0.0.1", 0), Handler))
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        try:
            address = f"https://127.0.0.1:{server.server_port}"
            self.assertEqual(PIN, client.identify(address)["tls_pin"])
            self.assertEqual([( "/identity", None)], seen)
            with self.assertRaises(client.BridgeError) as error:
                client.http_request({"url": address, "tls_pin": "00" * 32, "token": "do-not-send"}, "/api", method="GET")
            self.assertEqual("TLS_IDENTITY_MISMATCH", error.exception.value["error"]["code"])
            self.assertEqual(1, len(seen))
        finally:
            server.shutdown(); server.server_close(); thread.join(2)

    def test_unpinned_requests_and_plaintext_never_connect(self):
        with patch.object(client.http.client, "HTTPSConnection") as factory:
            for endpoint, authenticate in (("/api", True), ("/pair/status", False), ("/pair/request", False)):
                with self.assertRaises(client.BridgeError):
                    client.post({"url": "phone", "token": "secret", "_bootstrap": True}, endpoint, {}, authenticate)
            with self.assertRaises(ValueError):
                client.identify("http://phone:8443")
            factory.assert_not_called()

    def exchange(self, calls):
        def post(config, endpoint, payload, authenticate=False):
            calls.append((endpoint, copy.deepcopy(payload)))
            if endpoint == "/pair/request":
                return {**self.identity, "status": "committed", "protocol": client.PAIRING_PROTOCOL,
                        "pairing_id": "pair", "pairing_secret": "secret", "server_nonce": "22" * 32,
                        "expires_in": 999999, "poll_interval": 0, "verification_code": "ATTACKER",
                        "user_confirmed": True, "deadline": 999999999999, "token": "forged-token"}
            if endpoint == "/pair/reveal":
                self.assertEqual(calls[0][1]["commitment"], client.pairing_digest("commit", PIN, payload["client_nonce"]).hex())
                return {**self.identity, "status": "pending", "verification_code": "ATTACKER"}
            return {**self.identity, "status": "approved", "token": "token", "client_id": "client"}
        return post

    def test_code_is_local_and_server_cannot_fake_human_confirmation(self):
        calls = []
        with patch.object(client, "identify", return_value=self.identity), patch.object(client, "post", side_effect=self.exchange(calls)):
            first = self.connection.connect("phone")
            expected = client.pairing_code(PIN, calls[1][1]["client_nonce"], "22" * 32)
            self.assertEqual(expected, first["verification_code"])
            self.assertTrue(first["confirmation_required"])
            self.assertEqual(2, first["poll_interval"])
            self.assertNotIn("token", self.connection.config["devices"]["phone"]["pending"])
            second = client.Connection(self.connection.store.path).connect(device_id="phone")
            self.assertTrue(second["confirmation_required"])
            self.assertEqual(2, len(calls))  # No claim until human confirms on this side.
            self.assertNotIn("token", self.connection.config["devices"]["phone"])
            approved = self.connection.connect(device_id="phone", confirm_pairing=True)
            self.assertEqual("paired", approved["status"])
            self.assertNotIn("token", approved)

    def test_new_pairing_cannot_be_preconfirmed(self):
        with patch.object(client, "identify", return_value=self.identity), patch.object(client, "post") as post:
            with self.assertRaises(client.BridgeError):
                self.connection.connect("phone", confirm_pairing=True)
            post.assert_not_called()

    def test_existing_pin_cannot_be_overwritten_by_new_address_or_identity_response(self):
        self.connection._update("phone", url="https://old:8443", tls_pin=PIN, token="old-secret")
        with patch.object(client, "identify", return_value={**self.identity, "tls_pin": "00" * 32}), patch.object(client, "post") as post:
            with self.assertRaises(client.BridgeError):
                self.connection.connect("new", "phone")
            post.assert_not_called()
        self.assertEqual(PIN, self.connection.config["devices"]["phone"]["tls_pin"])
        self.assertEqual("https://old:8443", self.connection.config["devices"]["phone"]["url"])

    def test_http_registry_tokens_are_discarded_even_with_device_id(self):
        self.connection.store.path.write_text(json.dumps({"version": 2, "devices": {"phone": {
            "url": "http://phone:8443", "device_id": "phone", "token": "exposed", "pending": {"pairing_secret": "exposed"}}}}))
        saved = self.connection.config["devices"]["phone"]
        self.assertNotIn("token", saved)
        self.assertNotIn("pending", saved)
        self.assertEqual("https://phone:8443", saved["url"])


if __name__ == "__main__":
    unittest.main()
