#!/usr/bin/env python3
"""Python 3.10+ multi-device client and MCP stdio bridge; no dependencies.

Credentials are bound to installation IDs, never merely to network addresses.
Input actions are never automatically retried, including after a disconnect.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager, ExitStack
import copy
from functools import lru_cache
import hashlib
import hmac
import http.client
import json
import os
import secrets
import ssl
from pathlib import Path
import sys
import tempfile
import threading
import time
import wave
from urllib.parse import urlsplit
from phoneuse_adb import AdbDevice, BridgeError
from phoneuse_adb import validate_arguments


class HttpError(RuntimeError):
    def __init__(self, status, value):
        error = value.get("error", {})
        self.status = status
        self.code = error.get("code") if isinstance(error, dict) else None
        super().__init__(f"HTTP {status}: {error.get('message', self.code) if isinstance(error, dict) else error}")


def prepare_voice_file(arguments, device_id):
    path_value = arguments.pop("audio_file", None)
    try:
        if not isinstance(path_value, str) or not path_value or "pcm_base64" in arguments or "sample_rate" in arguments:
            raise ValueError("Provide audio_file only; the bridge reads the WAV format")
        path = Path(path_value).expanduser()
        if not path.is_file():
            raise ValueError("audio_file must be a regular WAV file on the bridge host")
        with wave.open(str(path), "rb") as audio:
            rate, frames = audio.getframerate(), audio.getnframes()
            if (audio.getnchannels() != 1 or audio.getsampwidth() != 2 or audio.getcomptype() != "NONE"
                    or not 8000 <= rate <= 48000 or not 1 <= frames <= 30 * rate):
                raise ValueError("Use a mono PCM16 WAV, 8000..48000 Hz, up to 30 seconds")
            pcm = audio.readframes(frames)
            if len(pcm) != 2 * frames:
                raise ValueError("Incomplete WAV audio")
    except (ValueError, OSError, wave.Error, EOFError) as error:
        raise BridgeError("INVALID_ARGUMENT", str(error) or "Invalid WAV file", device_id, action_executed=False) from error
    arguments.update(sample_rate=rate, pcm_base64=base64.b64encode(pcm).decode("ascii"))


def origin(value):
    if "://" not in value:
        value = "https://" + value
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment or url.port == 0:
        raise ValueError("address must be an https://host:port origin; plaintext HTTP is disabled")
    host = f"[{url.hostname}]" if ":" in url.hostname else url.hostname
    return f"https://{host}:{url.port or 8443}"


PAIRING_PROTOCOL = "phoneuse-sas-v1"


def checked_hex(value):
    if not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
        raise ValueError("Expected 32 bytes of lowercase hex")
    return value


def pairing_digest(purpose, *fields):
    return hashlib.sha256("\0".join((PAIRING_PROTOCOL, purpose, *map(checked_hex, fields))).encode("ascii")).digest()


def pairing_code(pin, client_nonce, server_nonce):
    return f"{int.from_bytes(pairing_digest('sas', pin, client_nonce, server_nonce)[:4], 'big') % 100_000_000:08d}"


def public_key_pin(certificate):
    """Extract the X.509 SubjectPublicKeyInfo DER (already parsed by OpenSSL during TLS).

    A bounded DER reader keeps the portable bridge standard-library-only. This is
    not a certificate validator: TLS verifies key possession, pairing authenticates
    this SPKI, and every subsequent socket is checked against the saved pin.
    """
    def field(offset, limit):
        start = offset
        if offset + 2 > limit:
            raise ValueError("Truncated TLS certificate")
        tag, size = certificate[offset:offset + 2]
        offset += 2
        if size & 128:
            count = size & 127
            if not 1 <= count <= 4 or offset + count > limit:
                raise ValueError("Invalid certificate DER length")
            size = int.from_bytes(certificate[offset:offset + count], "big")
            offset += count
        end = offset + size
        if end > limit:
            raise ValueError("Truncated TLS certificate field")
        return tag, start, offset, end
    tag, _, body, end = field(0, len(certificate))
    if tag != 0x30 or end != len(certificate):
        raise ValueError("Invalid X.509 certificate")
    tag, _, cursor, limit = field(body, end)
    if tag != 0x30:
        raise ValueError("Invalid TBSCertificate")
    if cursor >= limit:
        raise ValueError("Empty TBSCertificate")
    if certificate[cursor] == 0xA0:  # optional explicit version
        cursor = field(cursor, limit)[3]
    for _ in range(5):  # serial, signature, issuer, validity, subject
        cursor = field(cursor, limit)[3]
    tag, start, _, end = field(cursor, limit)
    if tag != 0x30:
        raise ValueError("Invalid SubjectPublicKeyInfo")
    return hashlib.sha256(certificate[start:end]).hexdigest()


def http_request(config, endpoint, payload=None, authenticate=True, method="POST"):
    url = urlsplit(origin(config["url"]))
    # Standalone conditional reads can use a 60 s budget plus queueing time.
    # Actions still return their original request state after the 15 s window.
    tool = (payload or {}).get("tool") if endpoint == "/api" else ((payload or {}).get("params") or {}).get("name")
    pin = config.get("tls_pin")
    bootstrap = config.get("_bootstrap") and endpoint == "/identity" and method == "GET" and not authenticate
    if not pin and not bootstrap:
        raise BridgeError("TLS_PAIRING_REQUIRED", "Pair again and compare the codes on the phone; unbound credentials cannot be sent", action_executed=False)
    if pin:
        checked_hex(pin)
    # Public CA validation is replaced by mandatory SPKI verification before HTTP
    # bytes are sent. Only a credential-free identity probe may bootstrap a pin.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    timeout = 4 if endpoint == "/identity" else 90 if tool == "observe" else 25
    connection = http.client.HTTPSConnection(url.hostname, url.port or 8443, context=context, timeout=timeout)
    headers = {"Accept": "application/json, text/event-stream"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if authenticate:
        headers["Authorization"] = "Bearer " + config["token"]
    if endpoint == "/mcp":
        headers["MCP-Protocol-Version"] = "2025-11-25"
    try:
        connection.connect()
        actual_pin = public_key_pin(connection.sock.getpeercert(binary_form=True))
        if pin and not hmac.compare_digest(pin, actual_pin):
            raise BridgeError("TLS_IDENTITY_MISMATCH", "Phone TLS key changed. No credentials sent. Verify the device independently before explicitly forgetting its binding and pairing again", action_executed=False)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        connection.request(method, endpoint, body, headers)
        response = connection.getresponse()
        body = response.read(16 * 1024 * 1024 + 1)
        if len(body) > 16 * 1024 * 1024:
            raise ValueError("Response too large")
        if response.status == 202 and not body:
            return None
        value = json.loads(body)
        if not isinstance(value, dict):
            raise ValueError("Expected a JSON object from the phone")
        if response.status not in (200, 202):
            raise HttpError(response.status, value)
        if endpoint == "/identity":
            value["_tls_pin"] = actual_pin  # Local socket evidence; overwrite any server-supplied field.
        return value
    finally:
        connection.close()


def post(config, endpoint, payload, authenticate=True):
    return http_request(config, endpoint, payload, authenticate)


def identify(address, tls_pin=None):
    # Do not put any credential in this config, even when reconnecting.
    value = http_request({"url": origin(address), "tls_pin": tls_pin, "_bootstrap": tls_pin is None}, "/identity", authenticate=False, method="GET")
    for field in ("device_id", "service_instance_id"):
        if not isinstance(value.get(field), str) or not value[field]:
            raise BridgeError("IDENTITY_UNAVAILABLE", f"The phone must provide {field}; update Phone Use and pair again", action_executed=False)
    return {**{key: value.get(key) for key in
            ("device_id", "name", "model", "android_version", "is_emulator", "service_instance_id")}, "tls_pin": value["_tls_pin"]}


@contextmanager
def file_lock(path, timeout=60):
    """OS releases the lock on process exit; the harmless lock file remains."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as stream:
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b"\0")
            stream.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise BridgeError("BRIDGE_BUSY", "Another local client is still using this device/configuration", action_executed=False) from None
                time.sleep(0.025)
        try:
            yield
        finally:
            if os.name == "nt":
                import msvcrt
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class ConfigStore:
    def __init__(self, path):
        self.path = Path(path).expanduser().resolve()
        self.mutex = threading.RLock()

    def _read(self):
        value = json.loads(self.path.read_text(encoding="utf-8-sig")) if self.path.exists() else {}
        if not isinstance(value, dict):
            raise ValueError("Phone Use config must be a JSON object")
        if value.get("version") == 2:
            if not isinstance(value.get("devices"), dict):
                raise ValueError("Invalid device registry")
            for device in value["devices"].values():
                if device.get("adb_marker") and not device.get("url"):
                    continue
                if not device.get("tls_pin") or device.get("url", "").startswith("http://"):
                    for key in ("token", "client_id", "pending", "tls_pin"):
                        device.pop(key, None)
                    device["url"] = origin(device["url"].replace("http://", "https://", 1))
            return value
        if value.get("version") is not None:
            raise ValueError("Unsupported Phone Use config version")
        upgraded = {"version": 2, "devices": {}}
        if value.get("url"):
            upgraded["legacy_address"] = origin(value["url"].replace("http://", "https://", 1))
            upgraded["legacy_pairing_requires_approval"] = bool(value.get("token") or value.get("pending"))
            if isinstance(value.get("device_id"), str) and value["device_id"]:
                upgraded["devices"][value["device_id"]] = {"device_id": value["device_id"], "url": upgraded["legacy_address"]}
        return upgraded

    def _write(self, value):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".phoneuse-", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(value, stream, ensure_ascii=False, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    @contextmanager
    def transaction(self, write=False):
        with self.mutex, file_lock(self.path.with_suffix(self.path.suffix + ".lock")):
            value = self._read()
            yield value
            if write:
                self._write(value)

    def read(self):
        with self.transaction() as value:
            return copy.deepcopy(value)


class Connection:
    """Independent device connections; deliberately no mutable current device."""
    def __init__(self, path, url=None, name="AI client"):
        self.store, self.url, self.name = ConfigStore(path), origin(url) if url else None, name
        self._mutex = threading.Lock()
        self._locks, self._catalogs = {}, {}

    @property
    def config(self):
        return self.store.read()

    @contextmanager
    def device_lock(self, device_id):
        if not isinstance(device_id, str) or not device_id:
            raise BridgeError("DEVICE_REQUIRED", "Specify device_id for every device operation", action_executed=False)
        with self._mutex:
            lock = self._locks.setdefault(device_id, threading.RLock())
        digest = hashlib.sha256(device_id.encode("utf-8")).hexdigest()
        path = self.store.path.parent / (self.store.path.name + ".devices") / (digest + ".lock")
        with lock, file_lock(path):
            yield

    def _device(self, device_id):
        device = self.config["devices"].get(device_id)
        if device is None:
            raise BridgeError("DEVICE_NOT_FOUND", "Unknown device_id; call phoneuse_connect(address) first", device_id, action_executed=False)
        return device

    def _update(self, device_id, **fields):
        with self.store.transaction(write=True) as config:
            config["devices"].setdefault(device_id, {"device_id": device_id}).update(fields)

    def _forget_pairing(self, device_id):
        with self.store.transaction(write=True) as config:
            for key in ("token", "client_id", "pending"):
                config["devices"][device_id].pop(key, None)
        self._catalogs.pop(device_id, None)

    @staticmethod
    def _live_adb(device):
        if device.get("adb_serial"):
            try:
                return AdbDevice(device["adb_serial"], device["adb_marker"])
            except BridgeError as error:
                if error.value["error"]["code"] != "ADB_UNAVAILABLE":
                    raise
        return None

    @staticmethod
    def _forwarded_identity(device):
        with device.app_origin() as address:
            try:
                return identify(address)
            except (OSError, http.client.HTTPException):
                return None  # The App may not be installed or running yet.

    def connect_adb(self, serial, device_id=None):
        adb_device = AdbDevice(serial)
        installation = self._forwarded_identity(adb_device)
        app_id = installation["device_id"] if installation else None
        def matches(key, item):
            return item.get("adb_marker") == adb_device.marker_id or (app_id and item.get("app_id", key) == app_id)
        keys = {k for k, v in self.config["devices"].items() if matches(k, v)}
        keys.update((adb_device.marker_id, app_id or adb_device.marker_id))
        with ExitStack() as stack:
            for key in sorted(keys):
                stack.enter_context(self.device_lock(key))
            config = stack.enter_context(self.store.transaction(write=True))
            devices = config["devices"]
            found = [k for k, v in devices.items() if matches(k, v)]
            if not keys.issuperset(found):
                raise BridgeError("BRIDGE_BUSY", "Registration changed during attachment; connect again", action_executed=False)
            if device_id and device_id not in found:
                raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Could not prove both connections belong to the specified device",
                                  device_id, action_executed=False)
            key = found[0] if found else app_id or adb_device.marker_id
            item = {}
            history = {}
            for match in found:
                current = devices[match]
                if current.get("adb_marker", adb_device.marker_id) != adb_device.marker_id or (
                        app_id and current.get("app_id", app_id) != app_id):
                    raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Conflicting device binding", key, action_executed=False)
                if installation and current.get("tls_pin", installation["tls_pin"]) != installation["tls_pin"]:
                    raise BridgeError("TLS_IDENTITY_MISMATCH", "Forwarded App TLS key changed", key, action_executed=False)
                if len(found) > 1 and (current.get("lease") or {}).get("until", 0) > time.time():
                    raise BridgeError("DEVICE_RESERVED", "Release outstanding reservations before merging devices", key, action_executed=False)
                recent = {rid: entry for rid, entry in current.get("requests", {}).items() if time.time() - entry["time"] < 600}
                if history.keys() & recent.keys():
                    raise BridgeError("REQUEST_ID_CONFLICT", "Overlapping request histories; reconnect after retention expires", key, action_executed=False)
                history.update(recent)
                item.update(current)
            for match in found[1:]:
                del devices[match]
                self._catalogs.pop(match, None)
            devices[key] = item
            item.update(device_id=key, requests=history)
            item.update(adb_serial=serial, adb_marker=adb_device.marker_id)
            if installation and not item.get("app_id"):
                item.update(app_id=installation["device_id"], tls_pin=installation["tls_pin"])
        return {"device_id": key, "adb_serial": serial, "status": "connected"}

    def disconnect(self, device_id, backend):
        if backend not in ("app", "adb"):
            raise ValueError("backend must be app or adb")
        with self.device_lock(device_id):
            self._device(device_id)
            if backend == "app":
                self._update(device_id, app_disabled=True)
            else:
                self._update(device_id, adb_serial=None)
        self._catalogs.pop(device_id, None)
        return {"device_id": device_id, "status": "disconnected", "backend": backend}

    def _request_route(self, device_id, tool, args, backend, instance, owner=None):
        """Commit ownership before dispatch; survives CLI exit or a lost reply."""
        rid = args.get("request_id")
        if not isinstance(rid, str) or not 1 <= len(rid) <= 128:
            raise BridgeError("INVALID_ARGUMENT", "Supply a unique request_id (1..128 characters)", device_id, action_executed=False)
        normalized = {k: v for k, v in args.items() if k not in ("device_id", "service_instance_id")}
        signature = hashlib.sha256(json.dumps([tool, normalized], sort_keys=True).encode()).hexdigest()
        now = time.time()
        with self.store.transaction(write=True) as config:
            device = config["devices"][device_id]
            history = {k: v for k, v in device.get("requests", {}).items() if now - v["time"] < 600}
            device["requests"] = history
            if rid in history:
                old = history[rid]
                if old["signature"] != signature:
                    raise BridgeError("REQUEST_ID_CONFLICT", "Request ID already used for different arguments", device_id, action_executed=False)
                if old["backend"] != backend or old["instance"] != instance or old.get("owner") != owner:
                    raise BridgeError("RESULT_UNKNOWN", "Original executor changed; query status and observe before continuing",
                                      device_id, action_executed=None)
                return old
            if len(history) >= 256:
                raise BridgeError("BUSY", "Request history full", device_id, action_executed=False)
            history[rid] = {"signature": signature, "backend": backend, "instance": instance, "owner": owner, "time": now}
        return None

    def _verify(self, device_id, device):
        try:
            identity = identify(device["url"], device.get("tls_pin"))
        except Exception as error:
            if isinstance(error, BridgeError):
                error.value["device_id"] = device_id
                raise
            raise BridgeError("DEVICE_OFFLINE", str(error), device_id, action_executed=False) from None
        if identity["device_id"] != device.get("app_id", device_id):
            raise BridgeError("DEVICE_IDENTITY_MISMATCH", "The address now belongs to another installation; no saved credential was sent", device_id,
                              actual_device_id=identity["device_id"], action_executed=False)
        self._update(device_id, **{k: v for k, v in identity.items() if k != "device_id"})
        return identity

    @staticmethod
    def _check_response(value, device_id, instance=None):
        if not isinstance(value, dict):
            raise BridgeError("IDENTITY_UNAVAILABLE", "Response lacks device/service identity; do not trust or replay the request", device_id, action_executed=None)
        for path, item in result_objects(value):
            # Older phones may omit nested identity; never ignore a supplied one.
            if path and not ("device_id" in item or "service_instance_id" in item):
                continue
            if not item.get("device_id") or not item.get("service_instance_id"):
                raise BridgeError("IDENTITY_UNAVAILABLE", "Response lacks device/service identity; do not trust or replay the request", device_id, action_executed=None)
            if item["device_id"] != device_id:
                raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Response came from a different device; do not replay the request", device_id, action_executed=None)
            if instance and item["service_instance_id"] != instance:
                raise BridgeError("SERVICE_INSTANCE_CHANGED", "Phone service restarted during the request; query status and observe again; do not replay actions", device_id, action_executed=None)

    def _send_locked(self, device_id, endpoint, payload, identity=None, device=None):
        device = device or self._device(device_id)
        if not device.get("token"):
            raise BridgeError("NOT_PAIRED", "Call phoneuse_connect and approve on this phone first", device_id, action_executed=False)
        identity = identity or self._verify(device_id, device)
        app_id = identity["device_id"]
        payload = copy.deepcopy(payload)
        arguments = tool = None
        if endpoint == "/api":
            arguments = payload.setdefault("arguments", {})
        elif endpoint == "/mcp" and payload.get("method") == "tools/call":
            arguments = payload.setdefault("params", {}).setdefault("arguments", {})
        if arguments is not None:
            if not isinstance(arguments, dict):
                raise ValueError("arguments must be an object")
            if arguments.get("device_id", device_id) != device_id:
                raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Nested device_id conflicts with the explicit route", device_id, action_executed=False)
            arguments["device_id"] = app_id
            # Honor an explicitly supplied instance to reject stale callers.
            arguments.setdefault("service_instance_id", identity["service_instance_id"])
            tool = payload.get("tool") if endpoint == "/api" else payload["params"].get("name")
            if tool == "send_voice" and "audio_file" in arguments:
                prepare_voice_file(arguments, device_id)
            if tool in ACTIONS and arguments.get("request_id"):
                self._request_route(device_id, tool, arguments, "app", identity["service_instance_id"], device.get("client_id"))
        try:
            value = post(device, endpoint, payload)
        except HttpError as error:
            if error.status == 401:
                self._forget_pairing(device_id)
                raise BridgeError("PAIRING_REVOKED", "Pairing was revoked; call phoneuse_connect to request approval again", device_id, action_executed=False) from None
            raise
        if endpoint == "/api":
            self._check_response(value, app_id, identity["service_instance_id"])
            if tool == "get_capabilities" and not value.get("error"):
                app_features(value)
            for _, envelope in result_objects(value):
                if "device_id" in envelope:
                    envelope["device_id"] = device_id
            self._record_app_lease(device_id, tool, value)
        if endpoint == "/mcp":
            if not isinstance(value, dict) or value.get("jsonrpc") != "2.0" or value.get("id") != payload.get("id") or ("result" in value) == ("error" in value):
                raise BridgeError("INVALID_REMOTE_RESPONSE", "Phone returned an invalid or mismatched JSON-RPC response; do not replay actions", device_id, action_executed=None)
            if "error" in value:
                error = value["error"]
                if not isinstance(error, dict) or not isinstance(error.get("code"), int) or not isinstance(error.get("message"), str):
                    raise BridgeError("INVALID_REMOTE_RESPONSE", "Phone returned an invalid JSON-RPC error", device_id, action_executed=None)
                details = error.get("data")
                error["data"] = {**(details if isinstance(details, dict) else {"remote_data": details} if details is not None else {}),
                                 "device_id": device_id, "service_instance_id": identity["service_instance_id"],
                                 "action_executed": False if error["code"] in (-32600, -32601, -32602) else None}
                return value
        if endpoint == "/mcp" and payload.get("method") == "tools/call":
            result = value.get("result", {})
            texts = [block for block in result.get("content", []) if block.get("type") == "text"] if isinstance(result, dict) else []
            if not texts:
                raise BridgeError("IDENTITY_UNAVAILABLE", "Tool response lacks a device-bound result; query status before continuing", device_id, action_executed=None)
            for block in texts:
                try:
                    item = json.loads(block["text"])
                except (ValueError, TypeError):
                    item = None
                self._check_response(item, app_id, identity["service_instance_id"])
                self._record_app_lease(device_id, tool, item)
                if tool == "get_capabilities" and not item.get("error"):
                    app_features(item)
                if device_id != app_id or tool == "get_capabilities":
                    for _, envelope in result_objects(item):
                        if "device_id" in envelope:
                            envelope["device_id"] = device_id
                    block["text"] = json.dumps(item, ensure_ascii=False)
            result.update(device_id=device_id, service_instance_id=identity["service_instance_id"])
        return value

    def _record_app_lease(self, key, tool, value):
        if tool == "acquire_device" and value.get("state") == "reserved":
            self._update(key, lease={"id": value["lease_id"], "backend": "app",
                                    "until": time.time() + value["expires_in_ms"] / 1000})
        if tool == "release_device" and value.get("state") == "released":
            self._update(key, lease=None)

    def send(self, device_id, endpoint, payload):
        with self.device_lock(device_id):
            device = self._device(device_id)
            if device.get("adb_marker"):
                return self._route(device_id, device, endpoint, payload)
            if device.get("app_disabled"):
                raise BridgeError("DEVICE_OFFLINE", "App connection disabled", device_id, action_executed=False)
            return self._send_locked(device_id, endpoint, payload)

    def _route(self, key, device, endpoint, payload):
        adb_device = self._live_adb(device)
        identity = None
        if device.get("token") and not device.get("app_disabled"):
            try:
                identity = self._verify(key, device)
            except BridgeError as error:
                if not adb_device or error.value["error"]["code"] != "DEVICE_OFFLINE":
                    raise
                # A Wi-Fi address may be unreachable while the App still owns
                # device actions. Try the same authenticated service over ADB.
                with adb_device.app_origin() as address:
                    try:
                        forwarded = identify(address, device["tls_pin"])
                    except (OSError, http.client.HTTPException):
                        forwarded = None
                    if forwarded:
                        if forwarded["device_id"] != device.get("app_id", key):
                            raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Unexpected App on the selected transport", key,
                                              action_executed=False)
                        return self._dispatch(key, {**device, "url": address}, endpoint, payload, adb_device, forwarded)
        return self._dispatch(key, device, endpoint, payload, adb_device, identity)

    def _dispatch(self, key, device, endpoint, payload, adb_device, identity):
        if not (adb_device or identity):
            raise BridgeError("DEVICE_OFFLINE", "Neither connection is reachable", key, action_executed=False)
        try:
            return self._dispatch_request(key, device, endpoint, payload, adb_device, identity)
        except BridgeError as error:
            # Business rejections use the same result envelope as the App. Keep
            # connection/identity errors on the transport error path.
            if error.value["error"]["code"] not in LOCAL_TOOL_ERRORS:
                raise
            request = payload if endpoint == "/api" else payload.get("params", {})
            tool = request.get("tool" if endpoint == "/api" else "name")
            value = error_value(error, key)
            value["service_instance_id"] = identity["service_instance_id"] if identity else adb_device.instance
            if tool in ACTIONS and "request_id" in request.get("arguments", {}):
                value["request_id"] = request["arguments"]["request_id"]
            if tool == "observe":
                value["observation_status"] = "failed"
            return route_result(endpoint, payload, value)

    def _dispatch_request(self, key, device, endpoint, payload, adb_device, identity):
        if endpoint == "/mcp" and payload.get("method") == "tools/list":
            if identity:
                return self._send_locked(key, endpoint, payload, identity, device)
            return {"jsonrpc": "2.0", "id": payload["id"], "result": {"tools": adb_catalog()}}
        request = payload if endpoint == "/api" else payload["params"]
        tool = request.get("tool" if endpoint == "/api" else "name")
        args = request.get("arguments", {})
        if not isinstance(args, dict):
            raise ValueError("arguments must be an object")
        if args.get("device_id", key) != key:
            raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Conflicting device_id", key, action_executed=False)
        if tool not in tool_schemas():
            raise BridgeError("UNKNOWN_TOOL", f"Unknown tool: {tool}", key, action_executed=False)
        if tool in ("get_capabilities", "acquire_device", "release_device", "get_status"):
            validate_arguments(args, tool_schemas()[tool])
        rid = args.get("request_id")
        original = device.get("requests", {}).get(rid) if isinstance(rid, str) else None
        if tool in ACTIONS and original and time.time() - original["time"] < 600:
            if original["backend"] == "app":
                available = (identity and identity["service_instance_id"] == original["instance"] and
                             device.get("client_id") == original.get("owner"))
            else:
                available = adb_device and adb_device.instance == original["instance"]
            if not available:
                raise BridgeError("RESULT_UNKNOWN", "Original executor unavailable; query the retained request",
                                  key, action_executed=None)

        def app_call(name, arguments):
            if (name in ACTIONS and lease and lease["backend"] == "adb" and
                    arguments.get("lease_id") == lease["id"]):
                arguments = {k: v for k, v in arguments.items() if k != "lease_id"}
            return self._send_locked(key, "/api", {"tool": name, "arguments": arguments}, identity, device)

        lease = device.get("lease")
        if lease and lease["until"] <= time.time():
            lease = None
        if lease and (tool in ACTIONS or tool in ("acquire_device", "release_device")) and args.get("lease_id") != lease["id"]:
            raise BridgeError("DEVICE_RESERVED", "Device is reserved; use the active lease", key, action_executed=False)
        if tool in ("acquire_device", "release_device") and (not identity or (lease and lease["backend"] == "adb")):
            instance = identity["service_instance_id"] if identity else adb_device.instance
            if args.get("service_instance_id", instance) != instance:
                raise BridgeError("SERVICE_INSTANCE_CHANGED", "Device executor changed", key, action_executed=False)
            if lease and lease["backend"] == "app":
                raise BridgeError("DEVICE_RESERVED", "Reconnect the App to manage this lease, or wait for expiry", key, action_executed=False)
            if not lease and ("lease_id" in args or tool == "release_device"):
                raise BridgeError("LEASE_EXPIRED", "Reservation expired", key, action_executed=False)
            if tool == "release_device":
                self._update(key, lease=None)
                value = {"state": "released", "in_flight_may_complete": False}
            else:
                ttl = args.get("ttl_ms", 60_000)
                if type(ttl) is not int or not 1000 <= ttl <= 300_000:
                    raise BridgeError("INVALID_ARGUMENT", "ttl_ms must be 1000..300000", key, action_executed=False)
                lease_id = lease["id"] if lease else secrets.token_hex(16)
                self._update(key, lease={"id": lease_id, "backend": "adb", "until": time.time() + ttl / 1000})
                value = {"state": "reserved", "lease_id": lease_id, "expires_in_ms": ttl, "lease_scope": "host_bridge"}
            return route_result(endpoint, payload, {**value, "device_id": key, "service_instance_id": instance})
        needs_accessibility = tool in ("get_capabilities", "send_voice", "observe") or tool in ADB_ACTIONS
        caps = app_call("get_capabilities", {}) if identity and needs_accessibility else {}
        if caps.get("error"):
            value = caps
        elif tool == "get_capabilities" or tool == "send_voice":
            instance = identity["service_instance_id"] if identity else adb_device.instance
            if args.get("service_instance_id", instance) != instance:
                raise BridgeError("SERVICE_INSTANCE_CHANGED", "Observe again after a backend change", key, action_executed=False)
            if identity and adb_device and caps.get("accessibility") and not caps.get("send_voice", {}).get("helper_attached"):
                # Retry startup on a later call if the App or ADB session restarted.
                if time.time() - device.get("voice_start_time", 0) > 5:
                    self._update(key, voice_start_time=time.time())
                    try:
                        process = adb_device.start_voice_helper()
                    except OSError as error:
                        caps.setdefault("send_voice", {})["unavailable_reason"] = str(error)
                    else:
                        for _ in range(10):
                            time.sleep(0.1)
                            caps = app_call("get_capabilities", {})
                            if caps.get("error") or caps.get("send_voice", {}).get("helper_attached") or process.poll() is not None:
                                break
            if caps.get("error"):
                return route_result(endpoint, payload, caps)
            value = combined_capabilities(key, caps, adb_device)
            if tool == "send_voice":
                if not value["features"]["send_voice"]:
                    raise BridgeError("VOICE_UNAVAILABLE", value["send_voice"]["unavailable_reason"], key, action_executed=False)
                value = app_call(tool, args)
        elif tool == "get_status" and (entry := device.get("requests", {}).get(args.get("request_id"))) and time.time() - entry["time"] < 600:
            if (entry["backend"] == "adb" or not identity or identity["service_instance_id"] != entry["instance"] or
                    entry.get("owner") != device.get("client_id")):
                value = {"device_id": key, "service_instance_id": entry["instance"], "request": entry.get("result") or
                         {"request_id": args["request_id"], "state": "unknown", "action_executed": None}}
            else:
                value = app_call(tool, args)
        elif identity and not (needs_accessibility and adb_device and not caps.get("accessibility")):
            if str(args.get("observation_id", "")).startswith("adb-"):
                raise BridgeError("STALE_OBSERVATION", "Observe with the active App before acting on this target", key, action_executed=False)
            value = app_call(tool, args)
            if (tool == "observe" and adb_device and args.get("mode") == "screenshot" and
                    set(args) <= {"mode", "device_id"} and (value.get("screenshot") or {}).get("reason") in
                    ("SCREENSHOT_RATE_LIMITED", "SCREENSHOT_TIMEOUT", "ACCESSIBILITY_UNAVAILABLE")):
                value = self._adb_call(key, device, adb_device, tool, args)
        else:
            if identity and tool in ADB_ACTIONS:
                status = app_call("get_status", {})
                if status.get("error") or (status.get("reservation") or {}).get("reserved"):
                    raise BridgeError("DEVICE_RESERVED", "App control is unavailable or reserved", key, action_executed=False)
            if tool in ADB_ACTIONS and "lease_id" in args and not lease:
                raise BridgeError("LEASE_EXPIRED", "Reservation expired", key, action_executed=False)
            value = self._adb_call(key, device, adb_device, tool, args)
        return route_result(endpoint, payload, value)

    def _adb_call(self, key, device, adb_device, tool, args):
        instance = adb_device.instance
        if args.get("service_instance_id", instance) != instance:
            raise BridgeError("SERVICE_INSTANCE_CHANGED", "Observe again on this backend", key, action_executed=False)
        if tool == "observe" or tool in ADB_ACTIONS:
            validate_arguments(args, tool_schemas()[tool])
        snapshots = {k: v for k, v in device.get("snapshots", {}).items() if time.time() - v["time"] < 30}
        def observe(options):
            result = adb_device.observe(options, snapshots)
            self._update(key, snapshots=dict(list(snapshots.items())[-8:]))
            return {**result, "device_id": key, "service_instance_id": instance, "observation_status": "complete"}
        if tool == "observe":
            return observe(args)
        if tool == "get_status":
            if "request_id" in args:
                raise BridgeError("REQUEST_NOT_FOUND", "No retained request with this ID", key, action_executed=False)
            lease = device.get("lease")
            reserved = bool(lease and lease["until"] > time.time())
            return {"device_id": key, "service_instance_id": instance, "stopped": False,
                    "reservation": {"reserved": reserved, "owned_by_client": reserved}}
        if tool not in ADB_ACTIONS:
            raise BridgeError("ACTION_UNSUPPORTED", f"{tool} requires the App backend", key, action_executed=False)
        # Consult the ledger before validating the old snapshot on retransmission.
        old = self._request_route(key, tool, args, "adb", instance)
        if old:
            if "result" in old:
                return old["result"]
            raise BridgeError("RESULT_UNKNOWN", "ADB action may have executed; do not replay", key, action_executed=None)
        result = {"device_id": key, "service_instance_id": instance, "request_id": args["request_id"]}
        try:
            cmd = adb_device.command(tool, args, snapshots)
            if "observe_after" in args and type(args["observe_after"]) is not bool:
                raise BridgeError("INVALID_ARGUMENT", "observe_after must be boolean", action_executed=False)
            if "observation_options" in args and (not args.get("observe_after") or not isinstance(args["observation_options"], dict)):
                raise BridgeError("INVALID_ARGUMENT", "observation_options requires observe_after", action_executed=False)
            try:
                adb_device.run("shell", "input", *cmd)
            except BridgeError as error:
                raise BridgeError("RESULT_UNKNOWN", str(error), action_executed=None) from error
            result.update(state="executed", action_executed=True, execution={"backend": "adb", "device_id": key,
                          "service_instance_id": instance}, observation_status="not_requested")
            if args.get("observe_after"):
                try:
                    result["observation"] = observe(args.get("observation_options", {"mode": "screenshot"}))
                    result["observation_status"] = "complete"
                except BridgeError as error:
                    result.update(observation_status="failed", observation=error_value(error, key))
        except BridgeError as error:
            result.update(error_value(error, key))
        # Keep the verdict across short-lived CLI processes without persisting
        # screenshots to the credential registry. A retry cannot repeat input.
        retained = without_image_data(result)
        for _, envelope in result_objects(retained):
            shot = envelope.get("screenshot")
            if isinstance(shot, dict) and shot.get("image_data_omitted"):
                shot.update(available=False, reason="IMAGE_NOT_RETAINED")
        with self.store.transaction(write=True) as config:
            config["devices"][key]["requests"][args["request_id"]]["result"] = retained
        return result

    def connect(self, address=None, device_id=None, confirm_pairing=False, adb_serial=None):
        if adb_serial is not None:
            if address or confirm_pairing:
                raise ValueError("Connect the ADB serial and pair the HTTPS address separately")
            return self.connect_adb(adb_serial, device_id)
        if not isinstance(confirm_pairing, bool):
            raise ValueError("confirm_pairing must be a boolean")
        config = self.config
        if address is None and device_id:
            saved = self._device(device_id)
            address = saved.get("url")
            if not address and saved.get("adb_serial"):
                return self.connect_adb(saved["adb_serial"], device_id)
        address = address or self.url or config.get("legacy_address")
        if not address:
            raise ValueError("Specify address (https://PHONE:8443), or device_id to reconnect")
        address = origin(address)
        known = config["devices"].get(device_id, {}) if device_id else next(
            (device for device in config["devices"].values() if device.get("url") == address), {})
        identity = identify(address, known.get("tls_pin"))
        app_id = identity["device_id"]
        actual = next((key for key, item in config["devices"].items() if item.get("app_id", key) == app_id), None)
        if actual is None:
            # An ADB-first registration keeps its public ID after the App is installed.
            for key, item in config["devices"].items():
                if item.get("adb_serial") and not item.get("app_id") and (not device_id or key == device_id):
                    adb_device = self._live_adb(item)
                    other = self._forwarded_identity(adb_device) if adb_device else None
                    if other and (other["device_id"], other["tls_pin"]) == (app_id, identity["tls_pin"]):
                        actual = key
                        break
        actual = actual or app_id
        if device_id and device_id != actual:
            raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Address does not match the requested installation; no saved credential was sent", device_id,
                              actual_device_id=actual, action_executed=False)
        with self.device_lock(actual):
            previous = self.config["devices"].get(actual, {})
            if previous.get("tls_pin") and not hmac.compare_digest(previous["tls_pin"], identity["tls_pin"]):
                raise BridgeError("TLS_IDENTITY_MISMATCH", "Saved phone TLS key differs; binding was not changed", actual, action_executed=False)
            self._update(actual, url=address, app_id=app_id, app_disabled=False, **{k: v for k, v in identity.items() if k != "device_id"})
            device = self._device(actual)
            shown = {**{k: v for k, v in identity.items() if k != "tls_pin"}, "device_id": actual, "address": address}
            if device.get("token"):
                status = self._send_locked(actual, "/api", {"tool": "get_status"})
                return {**shown, "service_instance_id": status["service_instance_id"], "status": "paired",
                        "message": "Connected using saved pairing. Device tools are ready."}
            pending = previous.get("pending")
            if pending and (time.time() >= pending["deadline"] or pending.get("service_instance_id") != identity["service_instance_id"]):
                self._forget_pairing(actual)
                raise BridgeError("PAIRING_EXPIRED", "Pairing expired or the service restarted; connect again to request approval", actual, action_executed=False)
            if pending:
                if confirm_pairing:
                    pending["user_confirmed"] = True
                    self._update(actual, pending=pending)
                if not pending.get("user_confirmed"):
                    return self._pending_result(shown, pending)
                verified = self._verify(actual, device)
                if verified["service_instance_id"] != pending["service_instance_id"]:
                    self._forget_pairing(actual)
                    raise BridgeError("PAIRING_EXPIRED", "Phone service restarted; connect again to request approval", actual, action_executed=False)
                try:
                    result = post(device, "/pair/status", {"pairing_id": pending["pairing_id"],
                                  "pairing_secret": pending["pairing_secret"]}, authenticate=False)
                except HttpError as error:
                    if error.code in ("PAIRING_EXPIRED", "PAIRING_DENIED"):
                        self._forget_pairing(actual)
                    raise
                self._check_response(result, app_id, identity["service_instance_id"])
                if result["status"] == "denied":
                    self._forget_pairing(actual)
                    raise BridgeError("PAIRING_DENIED", "Pairing denied on the phone", actual, action_executed=False)
                if result["status"] == "approved":
                    with self.store.transaction(write=True) as current:
                        saved = current["devices"][actual]
                        saved.update(token=result["token"], client_id=result["client_id"])
                        saved.pop("pending", None)
                        if current.get("legacy_address") == address:
                            current.pop("legacy_pairing_requires_approval", None)
                    return {**shown, "status": "paired", "message": "Pairing saved for this installation; use its device_id on every call."}
            else:
                if confirm_pairing:
                    raise BridgeError("PAIRING_CONFIRMATION_REQUIRED", "First display the new code, then ask the user to compare it; a new pairing cannot be pre-approved", actual, action_executed=False)
                nonce = secrets.token_hex(32)
                started = time.time()
                pending = post(device, "/pair/request", {"client_name": self.name, "protocol": PAIRING_PROTOCOL,
                    "commitment": pairing_digest("commit", device["tls_pin"], nonce).hex()}, authenticate=False)
                self._check_response(pending, app_id, identity["service_instance_id"])
                if pending.get("protocol") != PAIRING_PROTOCOL or pending.get("status") != "committed":
                    raise BridgeError("PAIRING_PROTOCOL_REQUIRED", "Phone does not support secure pairing; update it", actual, action_executed=False)
                # Freeze the transcript before revealing the committed nonce. Never
                # display a verification code supplied by the remote endpoint.
                code = pairing_code(device["tls_pin"], nonce, pending["server_nonce"])
                for field, maximum in (("pairing_id", 80), ("pairing_secret", 128)):
                    if not isinstance(pending.get(field), str) or not 1 <= len(pending[field]) <= maximum:
                        raise BridgeError("INVALID_REMOTE_RESPONSE", "Invalid pairing claim parameters", actual, action_executed=False)
                revealed = post(device, "/pair/reveal", {"pairing_id": pending["pairing_id"],
                    "pairing_secret": pending["pairing_secret"], "client_nonce": nonce}, authenticate=False)
                self._check_response(revealed, app_id, identity["service_instance_id"])
                if revealed.get("status") != "pending":
                    raise BridgeError("PAIRING_DENIED", "Pairing reveal rejected", actual, action_executed=False)
                # This is local security state. Never persist arbitrary response
                # fields (especially a forged user_confirmed/deadline/token).
                pending = {"pairing_id": pending["pairing_id"], "pairing_secret": pending["pairing_secret"],
                           "verification_code": code, "deadline": started + 120, "poll_interval": 2,
                           "service_instance_id": identity["service_instance_id"], "user_confirmed": False}
                self._update(actual, pending=pending)
            return self._pending_result(shown, pending)

    @staticmethod
    def _pending_result(shown, pending):
        return {**shown, "status": "pending", "verification_code": pending["verification_code"],
                "confirmation_required": not pending.get("user_confirmed", False), "poll_interval": pending["poll_interval"],
                "message": "Show this locally computed 8-digit code. Ask the user to compare ALL digits with the phone and approve there only if equal. Only after the user explicitly confirms the match, connect again with device_id and confirm_pairing=true. Never infer confirmation from network status, elapsed time, or remote phone tools. Never read or request a token."}

    def forget_device(self, device_id):
        with self.device_lock(device_id), self.store.transaction(write=True) as config:
            config["devices"].pop(device_id, None)
        self._catalogs.pop(device_id, None)
        return {"device_id": device_id, "status": "forgotten", "message": "Local binding removed. Revoke this client on the phone as well; the next connection requires a new code comparison."}

    def list_devices(self, refresh=True):
        config = self.config
        def status(item):
            device_id, saved = item
            metadata = {k: saved.get(k) for k in ("name", "model", "android_version", "is_emulator", "service_instance_id")}
            result = {**metadata, "device_id": device_id, "address": saved.get("url"), "paired": bool(saved.get("token")), "online": None}
            if saved.get("adb_marker"):
                result["adb_serial"] = saved.get("adb_serial")
            if refresh:
                adb_device = None
                try:
                    adb_device = self._live_adb(saved)
                    if not saved.get("url") or saved.get("app_disabled"):
                        return {**result, "mode": "adb" if adb_device else "offline", "online": bool(adb_device)}
                    try:
                        identity = identify(saved["url"], saved.get("tls_pin"))
                    except (OSError, http.client.HTTPException):
                        if not adb_device or not saved.get("token"):
                            raise
                        with adb_device.app_origin() as address:
                            identity = identify(address, saved["tls_pin"])
                    if identity["device_id"] != saved.get("app_id", device_id):
                        raise BridgeError("DEVICE_IDENTITY_MISMATCH", "Address belongs to another installation", device_id)
                    result.update(identity, device_id=device_id, online=True, mode="app+adb" if adb_device and saved.get("token") else
                                  "adb" if adb_device else "app")
                except Exception as error:
                    online = bool(adb_device)
                    result.update(online=online, mode="adb" if online else "offline", error=error_value(error, device_id)["error"])
            return result
        with ThreadPoolExecutor(max_workers=max(1, min(16, len(config["devices"])))) as pool:
            devices = list(pool.map(status, config["devices"].items()))
        result = {"devices": devices}
        if config.get("legacy_pairing_requires_approval"):
            result.update(legacy_address=config["legacy_address"], message="Legacy credentials have no verified device identity. Reconnect this address and approve on the phone to migrate safely.")
        return result

    def catalog(self):
        devices = [key for key, value in self.config["devices"].items() if
                   (value.get("token") and not value.get("app_disabled")) or value.get("adb_serial")]
        def fetch(device_id):
            try:
                value = self.send(device_id, "/mcp", {"jsonrpc": "2.0", "id": "catalog", "method": "tools/list"})
                self._catalogs[device_id] = value["result"]["tools"]
            except (OSError, http.client.HTTPException, RuntimeError, ValueError, KeyError):
                pass  # Offline phones do not prevent initialization or discovery.
            return self._catalogs.get(device_id, [])
        with ThreadPoolExecutor(max_workers=max(1, min(16, len(devices)))) as pool:
            catalogs = list(pool.map(fetch, devices))
        combined = {}
        for catalog in catalogs:
            for original in catalog:
                tool = copy.deepcopy(original)
                if tool["name"] in {item["name"] for item in CONNECTION_TOOLS}:
                    continue
                schema = tool.setdefault("inputSchema", {"type": "object"})
                schema.setdefault("properties", {}).update(DEVICE_FIELDS)
                schema["required"] = list(dict.fromkeys(["device_id", *schema.get("required", [])]))
                if tool["name"] == "send_voice":
                    fields = schema["properties"]
                    for field in ("pcm_base64", "sample_rate"):
                        fields.pop(field, None)
                    fields["audio_file"] = {"type": "string", "description": "WAV file on the bridge host (mono PCM16, 8–48 kHz, up to 30 s)"}
                    schema["required"] = [k for k in schema["required"] if k not in ("pcm_base64", "sample_rate")] + ["audio_file"]
                combined.setdefault(tool["name"], tool)
        return list(combined.values())

    def batch(self, calls, summary_only=False, continue_on_error=False):
        if not isinstance(calls, list) or not 1 <= len(calls) <= 100:
            raise ValueError("calls must contain between 1 and 100 explicit calls")
        groups = {}
        for index, item in enumerate(calls):
            if not isinstance(item, dict) or not isinstance(item.get("device_id"), str) or not item["device_id"] or not isinstance(item.get("tool"), str):
                raise ValueError("Each call requires device_id and tool")
            if not isinstance(item.get("arguments", {}), dict):
                raise ValueError("Each call's arguments must be an object")
            groups.setdefault(item["device_id"], []).append((index, item))
        def run(group):
            results, blocked = [], False
            for index, item in group:
                device_id = item["device_id"]
                if blocked:
                    result = {"device_id": device_id, "state": "skipped", "action_executed": False,
                              "error": {"code": "BATCH_PREVIOUS_INCOMPLETE", "message": "An earlier call on this device failed, is incomplete, or lacks its requested observation"}}
                else:
                    try:
                        arguments = copy.deepcopy(item.get("arguments", {}))
                        if "lease_id" in item:
                            arguments["lease_id"] = item["lease_id"]
                        result = self.send(device_id, "/api", {"tool": item["tool"], "arguments": arguments})
                    except Exception as error:
                        result = error_value(error, device_id)
                    if not continue_on_error:
                        effective = effective_result(item["tool"], result)
                        blocked = bool(effective.get("error") or effective.get("state") in ("failed", "cancelled", "unknown", "accepted", "executing") or
                                       observation_incomplete(effective, item.get("arguments", {})))
                results.append({"index": index, "device_id": device_id, "tool": item["tool"], "result": without_image_data(result)})
            return results
        with ThreadPoolExecutor(max_workers=min(16, len(groups))) as pool:
            results = sorted((item for group in pool.map(run, groups.values()) for item in group), key=lambda item: item["index"])
        summary = {"total": len(results), "succeeded": 0, "failed": 0, "pending": 0, "unknown": 0, "skipped": 0,
                   "states": {}, "error_codes": {}, "observation_error_codes": {},
                   "observation_incomplete": 0, "observation_statuses": {}, "condition_statuses": {}}
        for item in results:
            result = effective_result(item["tool"], item["result"])
            state = result.get("state", "failed" if result.get("error") else "completed")
            summary["states"][state] = summary["states"].get(state, 0) + 1
            outcome = "pending" if state in ("accepted", "executing") else state if state in ("unknown", "skipped") else "failed" if state in ("failed", "cancelled") or result.get("error") else "succeeded"
            summary[outcome] += 1
            summary["observation_incomplete"] += int(observation_incomplete(result, calls[item["index"]].get("arguments", {})))
            active = list(active_observations(result))
            statuses = {value.get("observation_status") for value in [result, *active] if value.get("observation_status")}
            for status in statuses:
                summary["observation_statuses"][status] = summary["observation_statuses"].get(status, 0) + 1
            for value in active:
                status = (value.get("condition") or {}).get("status")
                if status:
                    summary["condition_statuses"][status] = summary["condition_statuses"].get(status, 0) + 1
            errors = [(result, "error_codes")]
            if item["tool"] == "observe":
                errors.append((result, "observation_error_codes"))
            errors.extend((value, "observation_error_codes") for path, value in result_objects(result)
                          if path and path[0] in ("observation", "recovery_observation", "last_observation"))
            for value, key in errors:
                error = value.get("error")
                if isinstance(error, dict) and error.get("code"):
                    code = error["code"]
                    summary[key][code] = summary[key].get(code, 0) + 1
        return {"summary": summary, **({} if summary_only else {"results": results})}


def effective_result(tool, result):
    """A request status poll reports its action state inside `request`."""
    request = result.get("request")
    return request if tool == "get_status" and not result.get("error") and isinstance(request, dict) else result


RESULT_CHILDREN = ("request", "execution", "observation", "recovery_observation", "last_observation")


def result_objects(value, path=()):
    """Visit protocol result/observation envelopes, never arbitrary tree nodes."""
    if not isinstance(value, dict):
        return
    yield path, value
    for key in RESULT_CHILDREN:
        if isinstance(value.get(key), dict):
            yield from result_objects(value[key], (*path, key))


def active_observations(result):
    """Historical last_observation explains failures; it cannot establish readiness."""
    if any(key in result for key in ("observation_id", "tree", "screenshot", "condition")):
        yield result
    for key in ("observation", "recovery_observation"):
        if isinstance(result.get(key), dict):
            yield result[key]


def observation_incomplete(result, arguments=None):
    if result.get("observation_status") in ("observing", "failed", "condition_timeout"):
        return True
    if (arguments or {}).get("observe_after") and result.get("state") == "executed" and not isinstance(result.get("observation"), dict):
        return True
    for observation in active_observations(result):
        if observation.get("error") or observation.get("observation_status") in ("observing", "failed", "condition_timeout"):
            return True
        condition = observation.get("condition")
        if isinstance(condition, dict) and (condition.get("status") != "satisfied" or condition.get("timed_out")):
            return True
        if any(isinstance(observation.get(part), dict) and observation[part].get("available") is False for part in ("tree", "screenshot")):
            return True
    return False


DEVICE_FIELDS = {"device_id": {"type": "string", "description": "Stable logical device ID returned by phoneuse_connect/list_devices"}}
ADB_ACTIONS = {"tap", "long_press", "swipe", "back", "home", "recents"}
ACTIONS = ADB_ACTIONS | {"send_voice", "input_text", "scroll", "launch_app"}
LOCAL_TOOL_ERRORS = {"INVALID_ARGUMENT", "UNKNOWN_TOOL", "ACTION_UNSUPPORTED", "VOICE_UNAVAILABLE", "REQUEST_NOT_FOUND",
                     "STALE_OBSERVATION", "DEVICE_RESERVED", "LEASE_EXPIRED", "RESULT_UNKNOWN", "REQUEST_ID_CONFLICT",
                     "SCREEN_OFF", "DEVICE_LOCKED", "LOCAL_CONTROL_UI", "WINDOW_MISMATCH", "SCREENSHOT_UNAVAILABLE", "BUSY"}


def combined_capabilities(key, caps, adb_device):
    accessible = bool(caps.get("accessibility"))
    voice = dict(caps.get("send_voice") or {})
    ready = bool(accessible and adb_device and voice.get("routing_api_available"))
    reason = None if ready else ("App and live ADB connection required" if not (accessible and adb_device) else
                                 voice.get("unavailable_reason") or "Voice helper is not ready")
    voice.update(routing_api_available=ready, unavailable_reason=reason)
    return {**caps, "device_id": key, "service_instance_id": caps.get("service_instance_id") or adb_device.instance,
            "mode": ("app+adb" if adb_device else "app") if caps else "adb", "accessibility": accessible,
            "text_modes": caps.get("text_modes", []) if accessible else [],
            "send_voice": voice, "screenshot": caps.get("screenshot") if accessible else "adb screencap" if adb_device else "unavailable",
            "adb_actions": sorted(ADB_ACTIONS) if adb_device else [],
            "features": {"selection_replace": accessible and "insert" in caps.get("text_modes", []),
                         "screenshot": bool(accessible or adb_device), "send_voice": ready}}


def app_features(caps):
    """Older paired Apps expose the underlying signals without the feature summary."""
    accessible = bool(caps.get("accessibility"))
    voice = caps.get("send_voice") or {}
    caps.setdefault("mode", "app+adb" if voice.get("helper_attached") else "app")
    caps.setdefault("features", {"selection_replace": accessible and "insert" in caps.get("text_modes", []),
                                 "screenshot": accessible, "send_voice": accessible and bool(voice.get("routing_api_available"))})


def route_result(endpoint, payload, value):
    if endpoint == "/api":
        return value
    images = []
    for _, item in result_objects(value):
        shot = item.get("screenshot")
        if isinstance(shot, dict) and shot.get("data_base64"):
            images.append({"type": "image", "mimeType": shot["mime_type"], "data": shot.pop("data_base64")})
    incomplete = any(item.get("error") or observation_incomplete(item) for _, item in result_objects(value))
    result = tool_result(value, incomplete)
    result["content"] = images + result["content"]
    result.update(device_id=value.get("device_id"), service_instance_id=value.get("service_instance_id"))
    return {"jsonrpc": "2.0", "id": payload["id"], "result": result}


def adb_catalog():
    # Shipped snapshot of the App's ToolCatalog, checked by its JVM test.
    return json.loads(Path(__file__).with_name("phoneuse_catalog.json").read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def tool_schemas():
    return {tool["name"]: tool["inputSchema"] for tool in adb_catalog()}


def local_tool(name, description, properties, required=()):
    return {"name": name, "description": description, "inputSchema": {
        "type": "object", "properties": properties, "required": list(required), "additionalProperties": False}}


CONNECTION_TOOLS = [
    local_tool("phoneuse_connect", "Connect over pinned HTTPS. First show the 8-digit verification code to the user. Set confirm_pairing=true ONLY after the user explicitly says all digits match the phone and approves there; never infer this from a server response or inspect/approve through remote tools. Never returns credentials.",
               {"address": {"type": "string"}, "adb_serial": {"type": "string", "description": "Attach an already authorized serial from adb devices -l"},
                **DEVICE_FIELDS, "confirm_pairing": {"type": "boolean", "default": False}}),
    local_tool("phoneuse_list_devices", "List registered identities, names, models, Android versions, addresses, emulator flags and live reachability; no credentials.",
               {"refresh": {"type": "boolean", "default": True}}),
    local_tool("phoneuse_disconnect", "Disable one bridge connection and retain the logical device and pairing.",
               {**DEVICE_FIELDS, "backend": {"type": "string", "enum": ["app", "adb"]}}, ("device_id", "backend")),
    local_tool("phoneuse_call", "Call one phone tool by explicit device_id. Use acquire_device/release_device for optional developer reservations. Never replay uncertain actions.",
               {**DEVICE_FIELDS, "tool": {"type": "string"}, "arguments": {"type": "object"}}, ("device_id", "tool")),
    local_tool("phoneuse_batch", "Run explicit calls in order per device and concurrently across devices without retries. Stop that device's remaining calls on pending actions, unmet/unknown conditions or unavailable observations unless continue_on_error is explicit. Action counts and observation/condition counts are separate; executed never means business success. Images omitted.",
               {"calls": {"type": "array", "minItems": 1, "maxItems": 100, "items": {"type": "object", "properties": {
                   **DEVICE_FIELDS, "tool": {"type": "string"}, "arguments": {"type": "object"}, "lease_id": {"type": "string"}}, "required": ["device_id", "tool"], "additionalProperties": False}},
                "summary_only": {"type": "boolean", "default": False}, "continue_on_error": {"type": "boolean", "default": False}}, ("calls",))
]


def tool_result(value, error=False):
    return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": error}


def error_value(error, device_id=None):
    if isinstance(error, BridgeError):
        value = copy.deepcopy(error.value)
        if value.get("device_id") is None:
            value["device_id"] = device_id
        if "action_executed" in value:
            value["state"] = "failed" if value["action_executed"] is False else "unknown"
        return value
    return {"device_id": device_id, "error": {"code": getattr(error, "code", None) or "BRIDGE_ERROR", "message": str(error)},
            "action_executed": None, "state": "unknown",
            "message": "If an action was sent, its outcome may be unknown. Query its request_id; do not replay with a new ID."}


def bridge(connection):
    """Newline-delimited JSON-RPC; independent device queues never block stdin."""
    output_lock, workers = threading.Lock(), {}
    control = ThreadPoolExecutor(max_workers=8, thread_name_prefix="phoneuse-control")

    def emit(value):
        with output_lock:
            print(json.dumps(value, ensure_ascii=False), flush=True)

    def handle(request):
        device_id = None
        try:
            method, params = request["method"], request.get("params", {})
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            if method == "initialize":
                version = params.get("protocolVersion")
                result = {"protocolVersion": version if version in ("2025-03-26", "2025-06-18", "2025-11-25") else "2025-11-25",
                          "capabilities": {"tools": {"listChanged": True}}, "serverInfo": {"name": "phone-use", "version": "1.4.0"},
                          "instructions": "List devices, or phoneuse_connect(address) and approve once on that phone. Every device call requires device_id; there is no current device. Credentials stay in this bridge. Use unique request_id values for actions; never replay after connection failures. Use acquire_device/release_device to reserve a device for consecutive test steps."}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": CONNECTION_TOOLS + connection.catalog()}
            elif method == "tools/call":
                name, arguments = params.get("name"), params.get("arguments", {})
                if not isinstance(arguments, dict):
                    raise ValueError("arguments must be an object")
                arguments = copy.deepcopy(arguments)
                device_id = arguments.get("device_id")
                if name == "phoneuse_connect":
                    state = connection.connect(**arguments)
                    emit({"jsonrpc": "2.0", "id": request["id"], "result": tool_result(state)})
                    if state["status"] in ("paired", "connected"):
                        emit({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
                    return
                if name == "phoneuse_list_devices":
                    result = tool_result(connection.list_devices(**arguments))
                elif name == "phoneuse_disconnect":
                    result = tool_result(connection.disconnect(**arguments))
                elif name == "phoneuse_batch":
                    value = connection.batch(**arguments)
                    result = tool_result(value, bool(value["summary"]["failed"] or value["summary"]["unknown"] or value["summary"]["observation_incomplete"]))
                else:
                    device_id = arguments.pop("device_id", None)
                    if name == "phoneuse_call":
                        name, arguments = arguments["tool"], arguments.get("arguments", {})
                        if not isinstance(arguments, dict):
                            raise ValueError("arguments must be an object")
                    emit(connection.send(device_id, "/mcp", {**request, "params": {"name": name, "arguments": arguments}}))
                    return
            else:
                emit({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": "Method not found"}})
                return
            emit({"jsonrpc": "2.0", "id": request["id"], "result": result})
        except Exception as error:
            if request.get("method") == "tools/call":
                emit({"jsonrpc": "2.0", "id": request["id"], "result": tool_result(error_value(error, device_id), True)})
                if isinstance(error, BridgeError) and error.value["error"]["code"] == "PAIRING_REVOKED":
                    emit({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            else:
                emit({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32000, "message": str(error)}})

    try:
        for line in sys.stdin:
            try:
                request = json.loads(line)
            except ValueError:
                emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}})
                continue
            if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
                emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}})
                continue
            if "id" not in request:
                continue  # Notifications never execute actions.
            params = request.get("params", {})
            args = params.get("arguments", {}) if isinstance(params, dict) else {}
            device_id = args.get("device_id") if isinstance(args, dict) else None
            if request["method"] in ("initialize", "ping"):
                handle(request)
            elif isinstance(device_id, str) and device_id:
                if device_id not in workers:
                    if len(workers) >= 64:
                        emit({"jsonrpc": "2.0", "id": request["id"], "result": tool_result(error_value(
                            BridgeError("BRIDGE_BUSY", "At most 64 simultaneous device queues are supported", device_id, action_executed=False)), True)})
                        continue
                    workers[device_id] = ThreadPoolExecutor(max_workers=1, thread_name_prefix="phoneuse-device")
                workers[device_id].submit(handle, request)
            else:
                control.submit(handle, request)
    finally:
        control.shutdown(wait=True)
        for worker in workers.values():
            worker.shutdown(wait=True)


def without_image_data(value):
    if isinstance(value, list):
        return [without_image_data(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: without_image_data(item) for key, item in value.items() if key != "data_base64"}
    if "data_base64" in value:
        result["image_data_omitted"] = True
    return result


def save_screenshot(value, destination):
    candidates = [(path, item) for path, item in result_objects(value)
                  if isinstance(item.get("screenshot"), dict) and item["screenshot"].get("data_base64")
                  and item["screenshot"].get("available") is not False]
    if not candidates:
        value["screenshot_output_error"] = "The result contains no screenshot data"
        return
    source, observation = max(candidates, key=lambda item: item[1].get("captured_at_ms") or 0)
    screenshot = observation["screenshot"]
    path = Path(destination).expanduser().resolve()
    try:
        data = base64.b64decode(screenshot["data_base64"], validate=True)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        screenshot["saved_path"] = str(path)
        value["screenshot_output_source"] = ".".join((*source, "screenshot"))
    except (OSError, ValueError) as error:
        # Saving an image must not hide an already-executed action's result.
        value["screenshot_output_error"] = str(error)


def utf8_stdio():
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="strict")


def main():
    utf8_stdio()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path.home() / ".phoneuse.json")
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair")
    pair.add_argument("--url", "--address", dest="address")
    pair.add_argument("--device-id")
    pair.add_argument("--name", default="Python client")
    listing = sub.add_parser("list-devices")
    attach = sub.add_parser("connect-adb", help="Attach a single ADB transport to its logical device")
    attach.add_argument("--serial", required=True)
    attach.add_argument("--device-id", help="Require a verified match with this existing device")
    detach = sub.add_parser("disconnect", help="Disable one connection and retain its identity")
    detach.add_argument("--device-id", required=True)
    detach.add_argument("--backend", required=True, choices=("app", "adb"))
    listing.add_argument("--cached", action="store_true", help="Do not probe reachability")
    forget = sub.add_parser("forget-device", help="Explicitly remove a local TLS binding after independently verifying a key change")
    forget.add_argument("--device-id", required=True)
    call = sub.add_parser("call")
    call.add_argument("tool")
    call.add_argument("arguments", nargs="?", default="{}", help="JSON object, or @path to a UTF-8 JSON file")
    call.add_argument("--device-id", required=True)
    call.add_argument("--screenshot-out", type=Path)
    call.add_argument("--include-image-data", action="store_true", help="Include base64 images in JSON; omitted by default")
    tree = call.add_mutually_exclusive_group()
    tree.add_argument("--full-tree", action="store_true")
    tree.add_argument("--compact", action="store_true", help="Request compact observation tree (the default)")
    batch = sub.add_parser("batch")
    batch.add_argument("calls", help="JSON array, or @path to a UTF-8 JSON file")
    batch.add_argument("--summary-only", action="store_true")
    batch.add_argument("--continue-on-error", action="store_true")
    mcp = sub.add_parser("mcp-stdio")
    mcp.add_argument("--url", help="Optional address hint for first pairing")
    mcp.add_argument("--name", default="AI client")
    args = parser.parse_args()
    connection = Connection(args.config, getattr(args, "url", None), getattr(args, "name", "Python client"))
    if args.command == "pair":
        shown_code = None
        confirmed = False
        while True:
            result = connection.connect(args.address, args.device_id, confirm_pairing=confirmed)
            if result["status"] in ("paired", "connected"):
                break
            if result["verification_code"] != shown_code:
                shown_code = result["verification_code"]
                print(f"Approve '{args.name}' on {result.get('name') or result['device_id']} ({result['device_id']}). Verification code: {shown_code}", file=sys.stderr, flush=True)
            if result.get("confirmation_required"):
                print("Compare ALL eight digits with the phone and approve there. Type yes only if they match: ", file=sys.stderr, end="", flush=True)
                if sys.stdin.readline().strip().lower() != "yes":
                    raise BridgeError("PAIRING_CONFIRMATION_REQUIRED", "Pairing not confirmed; no credential accepted", result["device_id"], action_executed=False)
                confirmed = True
            args.device_id = result["device_id"]
            time.sleep(result["poll_interval"])
    elif args.command == "mcp-stdio":
        bridge(connection)
        return
    elif args.command == "list-devices":
        result = connection.list_devices(not args.cached)
    elif args.command == "connect-adb":
        result = connection.connect_adb(args.serial, args.device_id)
    elif args.command == "disconnect":
        result = connection.disconnect(args.device_id, args.backend)
    elif args.command == "forget-device":
        result = connection.forget_device(args.device_id)
    elif args.command == "batch":
        calls = json.loads(Path(args.calls[1:]).read_text(encoding="utf-8-sig") if args.calls.startswith("@") else args.calls)
        result = connection.batch(calls, args.summary_only, args.continue_on_error)
    else:
        raw = Path(args.arguments[1:]).read_text(encoding="utf-8-sig") if args.arguments.startswith("@") else args.arguments
        arguments = json.loads(raw)
        if not isinstance(arguments, dict):
            parser.error("arguments must be a JSON object")
        if args.full_tree or args.compact:
            if args.tool != "observe":
                parser.error("--full-tree/--compact apply to observe")
            arguments["compact"] = not args.full_tree
            if args.full_tree:
                arguments["output"] = "legacy"
        try:
            result = connection.send(args.device_id, "/api", {"tool": args.tool, "arguments": arguments})
        except Exception as error:
            if isinstance(error, BridgeError):
                raise
            raise BridgeError(getattr(error, "code", None) or "BRIDGE_ERROR", str(error), args.device_id, action_executed=None) from None
        if args.screenshot_out:
            save_screenshot(result, args.screenshot_out)
        if not args.include_image_data:
            result = without_image_data(result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps(error_value(error), ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
