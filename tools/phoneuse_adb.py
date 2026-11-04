"""ADB transport for the host bridge. Every command targets a verified transport."""
import base64
from contextlib import contextmanager
import hashlib
import math
import re
import struct
import subprocess
import time
import uuid


class BridgeError(RuntimeError):
    def __init__(self, code, message, device_id=None, **details):
        super().__init__(message)
        self.value = {"device_id": device_id, "error": {"code": code, "message": message}, **details}


def reject(code, message):
    raise BridgeError(code, message, action_executed=False)


def validate_arguments(value, schema, path="arguments"):
    """Validate the JSON Schema subset emitted by ToolCatalog before dispatch."""
    types = {"string": (str,), "boolean": (bool,), "integer": (int,), "number": (int, float),
             "object": (dict,), "array": (list,)}
    if type(value) not in types[schema["type"]] or ("enum" in schema and value not in schema["enum"]):
        reject("INVALID_ARGUMENT", f"Invalid {path}")
    if isinstance(value, dict):
        fields = schema["properties"]
        if set(value) - fields.keys() or set(schema.get("required", [])) - value.keys():
            reject("INVALID_ARGUMENT", f"Unexpected or missing fields in {path}")
        for name, child in value.items():
            validate_arguments(child, fields[name], f"{path}.{name}")
    elif isinstance(value, list):
        if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", 64):
            reject("INVALID_ARGUMENT", f"Invalid length for {path}")
        for child in value:
            validate_arguments(child, schema["items"], path)
    elif isinstance(value, str):
        if not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", len(value)):
            reject("INVALID_ARGUMENT", f"Invalid length for {path}")
    elif type(value) in (int, float):
        if not math.isfinite(value) or not schema.get("minimum", value) <= value <= schema.get("maximum", value):
            reject("INVALID_ARGUMENT", f"Out of range {path}")


def adb(*args, timeout=12):
    try:
        result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.TimeoutExpired) as error:
        raise BridgeError("ADB_UNAVAILABLE", str(error), action_executed=False) from error
    if result.returncode:
        reject("ADB_UNAVAILABLE", result.stderr.decode(errors="replace").strip() or "adb command failed")
    return result.stdout


class AdbDevice:
    marker = "/data/local/tmp/.phoneuse-device-id"

    def __init__(self, serial, expected=None):
        if not isinstance(serial, str) or not serial or any(c.isspace() for c in serial):
            reject("INVALID_ARGUMENT", "Specify one serial from adb devices -l")
        for line in adb("devices", "-l").decode().splitlines():
            fields = line.split()
            if fields[:2] == [serial, "device"]:
                ids = [f.removeprefix("transport_id:") for f in fields if f.startswith("transport_id:")]
                if len(ids) == 1 and ids[0].isdigit():
                    self.transport = ids[0]
                    break
        else:
            reject("ADB_UNAVAILABLE", "Selected ADB device is offline or unauthorized")
        if not expected:
            # Atomic creation. Concurrent hosts must converge on the same marker.
            candidate = str(uuid.uuid4())
            self.run("shell", f"umask 077; f={self.marker}.{candidate}; echo {candidate} > $f && "
                     f"(ln $f {self.marker} 2>/dev/null || test -f {self.marker}); r=$?; rm -f $f; exit $r")
        self.marker_id = self.run("shell", "cat", self.marker).decode().strip()
        try:
            if str(uuid.UUID(self.marker_id)) != self.marker_id:
                raise ValueError()
        except ValueError:
            reject("DEVICE_IDENTITY_MISMATCH", "Invalid ADB device identity")
        if expected and self.marker_id != expected:
            reject("DEVICE_IDENTITY_MISMATCH", "ADB serial now addresses another device")
        try:
            boot = self.run("shell", "cat", "/proc/sys/kernel/random/boot_id").decode().strip()
        except BridgeError:
            boot = self.run("shell", "settings", "get", "global", "boot_count").decode().strip()
        self.instance = f"adb-{self.marker_id}-{boot}-{self.transport}"

    def run(self, *args):
        return adb("-t", self.transport, *args)

    @contextmanager
    def app_origin(self):
        port = self.run("forward", "tcp:0", "tcp:8443").decode().strip()
        if not port.isdigit() or not 0 < int(port) < 65536:
            reject("ADB_UNAVAILABLE", "ADB could not allocate a local port")
        try:
            yield f"https://127.0.0.1:{port}"
        finally:
            try:
                self.run("forward", "--remove", "tcp:" + port)
            except BridgeError:
                # Losing a transport also removes its forwards. Preserve any
                # action result already received through this connection.
                pass

    def start_voice_helper(self):
        # The App accepts only one live shell Binder. Keep the process tied to
        # this ADB shell session (no nohup), including after the CLI exits.
        cmd = ('p=$(pm path ai.nidhoggfgg.phoneuse | head -n 1); '
               'test -n "$p" && CLASSPATH="${p#package:}" exec app_process / '
               'ai.nidhoggfgg.phoneuse.voice.VoiceShell')
        return subprocess.Popen(["adb", "-t", self.transport, "shell", "-T", cmd],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def frame(self):
        self.require_unlocked()
        data = self.run("exec-out", "screencap", "-p")
        if (len(data) < 33 or len(data) > 12 * 1024 * 1024 or
                not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR"):
            reject("SCREENSHOT_UNAVAILABLE", "Device did not return a bounded PNG screenshot")
        width, height = struct.unpack(">II", data[16:24])
        if not width or not height:
            reject("SCREENSHOT_UNAVAILABLE", "Invalid screenshot dimensions")
        return data, width, height

    def require_unlocked(self):
        power = self.run("shell", "dumpsys", "power").decode(errors="replace")
        if not re.search(r"^\s*mWakefulness=Awake\s*$", power, re.MULTILINE | re.IGNORECASE):
            reject("SCREEN_OFF", "ADB could not confirm an awake display")
        policy = self.run("shell", "dumpsys", "window", "policy").decode(errors="replace")
        showing = re.search(r"^\s*showing=(true|false)\s*$", policy, re.MULTILINE)
        if not showing or showing.group(1) == "true":
            reject("DEVICE_LOCKED", "ADB could not confirm the device is unlocked")

    def foreground(self):
        windows = self.run("shell", "dumpsys window | grep 'mCurrentFocus=' || true").decode(errors="replace")
        focus = re.findall(r"mCurrentFocus=(Window\{[^\n}]*?\bu\d+\s+([^\s/}]+)[^\n}]*\})", windows)
        if len(set(focus)) != 1:
            reject("WINDOW_MISMATCH", "Could not identify one focused window")
        return focus[0]

    def observe(self, args, snapshots):
        mode = args.get("mode", "both")
        if mode not in ("both", "screenshot", "tree"):
            reject("INVALID_ARGUMENT", "Unknown observation mode")
        if set(args) - {"mode", "device_id", "service_instance_id"}:
            reject("ACTION_UNSUPPORTED", "Advanced observation options require the App backend")
        if mode == "tree":
            reject("ACTION_UNSUPPORTED", "ADB observation supports screenshots; connect the App for a node tree")
        window, package = self.foreground()
        data, width, height = self.frame()
        if self.foreground()[0] != window:
            reject("WINDOW_MISMATCH", "Window changed while capturing the screen")
        now = time.time()
        observation_id = "adb-" + str(uuid.uuid4())
        snapshots[observation_id] = dict(instance=self.instance, time=now, window=window, digest=hashlib.sha256(data).hexdigest())
        return {"observation_id": observation_id, "captured_at_ms": int(now * 1000), "package_name": package,
                "display": {"width": width, "height": height, "id": 0, "coordinates": "physical_pixels",
                            "origin": "top_left", "includes_system_bars_and_ime": True},
                "tree": {"available": False, "reason": "APP_REQUIRED"} if mode == "both" else None,
                "screenshot": {"available": True, "mime_type": "image/png", "width": width, "height": height,
                               "scale_x": 1, "scale_y": 1, "offset_x": 0, "offset_y": 0,
                               "data_base64": base64.b64encode(data).decode("ascii")}}

    def command(self, tool, args, snapshots):
        fields = {"device_id", "service_instance_id", "request_id", "observation_id", "observe_after",
                  "observation_options", "lease_id"}
        if tool in ("tap", "long_press"):
            fields.update(("x", "y"))
        if tool in ("long_press", "swipe"):
            fields.add("duration_ms")
        if tool == "swipe":
            fields.add("points")
        if set(args) - fields:
            reject("ACTION_UNSUPPORTED", "Requested action fields require the App backend")
        if tool in ("tap", "long_press", "swipe") or "observation_id" in args:
            snapshot = snapshots.get(args.get("observation_id"))
            if not snapshot or snapshot["instance"] != self.instance or not 0 <= time.time() - snapshot["time"] < 30:
                reject("STALE_OBSERVATION", "Observe again on the current backend")
            data, width, height = self.frame()
            if hashlib.sha256(data).hexdigest() != snapshot["digest"]:
                reject("STALE_OBSERVATION", "Screen pixels changed; observe again")
        keys = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "recents": "KEYCODE_APP_SWITCH"}
        if tool in keys:
            self.require_unlocked()
            return ["keyevent", keys[tool]]
        if tool not in ("tap", "long_press", "swipe"):
            reject("ACTION_UNSUPPORTED", "Action requires the App backend")
        # A screenshot of a secure window may be black. Don't allow blind taps
        # on Phone Use pairing controls or System UI notifications/overlays.
        window, foreground = self.foreground()
        if snapshot.get("window") != window:
            reject("STALE_OBSERVATION", "Focused window changed; observe again")
        if "." not in foreground or foreground in ("ai.nidhoggfgg.phoneuse", "com.android.systemui"):
            reject("LOCAL_CONTROL_UI", "Cannot confirm a foreground application outside local control UI")
        points = args.get("points") if tool == "swipe" else [args]
        if not isinstance(points, list) or len(points) != (2 if tool == "swipe" else 1):
            reject("ACTION_UNSUPPORTED", "ADB swipe requires two points")
        coords = []
        for point in points:
            for axis, limit in (("x", width), ("y", height)):
                value = point.get(axis) if isinstance(point, dict) else None
                if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value < limit:
                    reject("INVALID_ARGUMENT", "Coordinates must be inside the display")
                coords.append(str(int(value)))
        if tool == "tap":
            return ["tap", *coords]
        duration = args.get("duration_ms", 600)
        if type(duration) is not int or not (500 if tool == "long_press" else 1) <= duration <= 5000:
            reject("INVALID_ARGUMENT", "Invalid gesture duration")
        return ["swipe", *coords, *(coords if tool == "long_press" else []), str(duration)]
