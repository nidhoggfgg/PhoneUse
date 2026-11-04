# API reference v0.4

**English** | [简体中文](api.zh-CN.md)

## Access and identity

The server listens on port `8443`, accepting **TLS 1.2/1.3 HTTPS and HTTP on the same socket**. By default, HTTP requires both socket endpoints and the requested Host to use `127.0.0.1`; other HTTP, including IPv6 loopback and whitelisted domains, is rejected before pairing/authentication. The persistent insecure-HTTP setting enables these other HTTP connections immediately; disabling it disconnects their existing requests and invalidates queued work. Both transports remain subject to the independent access address allowlist, normal pairing and authorization. Each installation retains its non-exportable Android Keystore key, certificate and SHA-256 SPKI pairing. Native clients continue to use pinned HTTPS without fallback.

### Commit → reveal → compare on both sides → approve → claim

AI clients use `phoneuse_connect` rather than handling credentials. The first call returns a locally computed eight-digit code. The user compares every digit against the phone and approves there, then explicitly tells the AI the codes match. Only then call `phoneuse_connect` with `device_id` and `confirm_pairing:true`. A remote `approved` response alone is not evidence of a human comparison. CLI `pair` asks for `yes` after showing the code. Existing pairings reconnect without confirmation.

The native wire protocol is specified in [Transport security](transport-security.md). All messages, including first contact, use HTTPS:

1. A credential-free `GET /identity` observes the TLS public key. Every following socket verifies the candidate pin before sending HTTP bytes.
2. `POST /pair/request` with `client_name`, `protocol:"phoneuse-sas-v1"` and `commitment` returns HTTP 202 with `status:"committed"`, `protocol`, `pairing_id`, `pairing_secret`, `server_nonce`, `expires_in:120` and `poll_interval:2`. No verification code is returned.
3. The bridge freezes that transcript and sends `POST /pair/reveal` with `pairing_id`, `pairing_secret` and `client_nonce`. A valid reveal returns `status:"pending"` and enables the phone approval UI. Both endpoints independently compute the eight-digit code.
4. After comparison and confirmation on both sides, poll `POST /pair/status` with `pairing_id` and `pairing_secret`. States are `committed`, `pending`, `denied`, or `approved`. Approval adds `client_id`, `token`, `token_type:"Bearer"`, `api_path:"/api"`, and `mcp_path:"/mcp"`.
5. Use `Authorization: Bearer TOKEN` over pinned HTTPS. Never send the code or secrets in URLs.

Requests expire two minutes after creation; approval does not extend that time. A service restart clears them. Repeat claims before expiry return the same credential. Only token digests are persisted on the phone. Limits are 16 requests, 16 clients, and at least five seconds between requests globally. An invalid commitment reveal permanently denies that request. A request cannot be approved before a valid reveal. Revocation clears retrievable credentials and invalidates queued inputs.

### Web endpoints and transport

`GET /` serves a public console without credentials or device data. Navigation from another page, including `Sec-Fetch-Site: cross-site`, is allowed, subject to Host validation. Scripts, pairing, and API requests retain same-origin checks. Framing is forbidden. `GET /app.js` is public; authenticated `GET /api/tools` returns `{"tools":[...]}` using the same parameter definitions as MCP `tools/list`.

API/MCP accept Bearer authorization or the browser's transport-specific pairing cookie; an explicit Authorization header takes precedence. POST bodies use `Content-Type: application/json`, support Content-Length or chunked transfer, and have a 64 KiB limit except for authenticated voice requests (4 MiB). With an empty access address allowlist, Host must match the receiving IP and port; domains are rejected. With a configured list, Host must exactly match a listed IP/domain and the service port; the receiving IP and loopback are not implicitly allowed. The list checks destination addresses, not client source IPs, and is read for each request. Origin, when present, must exactly match the request scheme and authority. Same-site and cross-site API requests are rejected; no CORS access is provided. Forwarding headers are not trusted. Native clients without Origin are allowed.

Browser pairing uses `POST /browser/pair/request` and `POST /browser/pair/status` with `{"client_name":"browser"}` and the original six-digit authorization code. Over HTTPS this requires independently trusted browser TLS; it is not the native eight-digit SAS protocol. Approval omits `token/token_type` and sets `phoneuse_client_PORT=...; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=34560000`. The cookie lasts up to 400 days, subject to browser cleanup policies. The Secure flag restricts cookies to HTTPS. JavaScript does not read the long-term credential.

HTTP uses a separate `phoneuse_http_client_PORT` cookie without Secure, with the same HttpOnly, SameSite and expiry attributes. HTTPS only reads its existing Secure cookie; HTTP only reads its own cookie. HTTP pairing does not provide transport encryption or TLS server identity.

- `GET /browser/session`: returns HTTP 200 with `{"paired":true,"client_id":"..."}` or `{"paired":false,"client_id":null}`; validates authorization without modifying cookies or issuing a new identity.
- `POST /browser/session`: authenticated Bearer or cookie plus `{}` exchanges the same identity for a persistent cookie. Old open tabs use it to migrate and delete the old sessionStorage token.
- `POST /browser/forget`: authenticated `{}` revokes the client, clears its cookie and cached pairing response, and invalidates queued inputs. Closing the page does not call it.

The console checks connections at startup, focus, and network recovery. Failed startup session reads retry every five seconds while preserving pairing. Inputs never retry automatically. The console no longer exports long-term credentials; older exported configurations can still be used by Python.

### App-bundled Skill distribution

The same listener serves these read-only routes without pairing:

| Path (GET) | Content |
| --- | --- |
| `/connect` | Plain-text Markdown onboarding for AI clients, including the current HTTPS origin |
| `/skills/manifest.json` | `schema_version`, `name`, `app_version`, `distribution:bundled`, archive `sha256`/`size`, `archive`, `entrypoint`, `skill`, and hash-bearing `connect_url` |
| `/skills/phone-use.zip` | Complete `phone-use/` directory with the Python client, Skill instructions, and API references; an `application/zip` attachment |
| `/skills/phone-use/SKILL.md` | The same instructions contained in the archive |

Only GET is supported; other methods return 405. `/connect` permits external navigation like the home page. Downloads retain same-origin checks; all routes enforce the host allowlist and HTTP setting. Only fixed packaged files are served, without credentials or control authorization. The manifest does not include device identity. The app's shared link includes `#sha256=...` for archive verification; fragments are not sent to the server. The bundled Skill updates with the app and does not query the Internet for updates.

### Persistent AI connections and identity

`python tools/phoneuse_client.py mcp-stdio` can initialize before pairing or while offline. Register an App with `phoneuse_connect(address=...)` or an authorized ADB device with `phoneuse_connect(adb_serial=...)`. `phoneuse_list_devices` reports one logical `device_id` per verified device. Every device call uses that ID. The App installation ID and TLS key are still checked on every App connection. `phoneuse_call` is available for hosts that do not refresh their tool list.

The CLI and stdio bridge expose the same tool schemas in App, ADB and combined modes. Inspect `get_capabilities.mode` (`app`, `adb`, `app+adb`) and `features.screenshot`, `features.selection_replace`, `features.send_voice` before choosing tools. Selection replacement uses `input_text(mode="insert")` on a readable editor with a current selection. An ADB screenshot uses physical pixels at native resolution. A snapshot belongs to the executor that produced it. Obtain a fresh observation when a backend changes. `phoneuse_disconnect(device_id=...,backend="app"|"adb")` disables the chosen bridge connection and retains its registration.

Public `GET /identity` returns `device_id/name/model/android_version/is_emulator/service_instance_id`. The installation UUID is independent of the address and local display name. Identity and pairing preferences are excluded from backup/migration. Before sending credentials, the bridge verifies the public device ID; it refuses to reuse a token for a different installation at the same address. Address changes must match saved identity. Legacy configurations without trusted IDs need new local approval. Every native request also verifies the saved TLS public key before sending HTTP data. HTTP-era credentials require new short-code pairing. Key mismatches fail closed and never overwrite the saved pin.

Pairing responses include device identity. Pending requests are persisted, and approved credentials are saved atomically. MCP results never return tokens or claim secrets. Devices have independent connections and locks; calls serialize per device and can run concurrently across devices. One offline phone does not block others. Observation IDs bind to a client, phone-service instance, and accessibility-service instance; cross-device, cross-client, and post-restart references are rejected.

The bridge manages `~/.phoneuse.json`, including pending claims, without returning it to MCP. HTTP 401 clears revoked credentials and requests an explicit connect call. Network errors neither delete credentials nor initiate pairing or replay input. This storage does not isolate files from other processes using the same OS account. Each HTTP connection handles one request and closes without affecting authorization.

Connection setup and blocking device requests use separate, bounded worker pools without waiting queues. When connection workers are full, new sockets close immediately; when device-request workers are full, authenticated blocking calls receive HTTP 503 with `error.code:"BUSY"` and `request_admitted:false`. Identity, status and cancellation do not use the blocking-request pool. A 503 does not establish the execution state of a previously submitted request ID (`action_executed:null`); query `get_status` for that ID. Admitted calls still wait for their result under the existing execution/observation limits.

## JSON API

Send `POST /api`:

```json
{"tool":"observe","arguments":{"mode":"both","max_dimension":1280}}
```

The response is the tool result. Device errors use HTTP 200 with `state/error`; authentication and malformed HTTP use appropriate HTTP errors. The shared ToolCatalog validates API/MCP names and types and rejects unknown fields.

| Tool | Arguments |
| --- | --- |
| `get_capabilities` | None |
| `get_status` | Optional `request_id`, restricted to this client's results; includes `paused` (always `false`, retained for compatibility), `stopped`, `executing_request_id` |
| `cancel` | Optional `request_id`; otherwise cancels all this client's unfinished inputs |
| `observe` | `mode`: tree / screenshot / both (default); `max_dimension`: 320–2048, default 1280; `compact`: true; `stable_wait_ms`: 0–2000, default 0; `max_attempts`: 1–3, default 3; other fields below |
| `acquire_device` | Optional `ttl_ms`: 1000–300000, default 60000; renewal requires current `lease_id` |
| `release_device` | `lease_id` belonging to this client's reservation |
| `list_apps` | None; only discoverable apps with a launch entry |
| `wait_for_change` | `ui_version`; `timeout_ms`: 1–10000, default 5000 |
| `tap` | `element_id`, or `x/y` |
| `long_press` | `element_id`, or `x/y`; coordinate `duration_ms`: 500–5000, default 600 |
| `send_voice` | Observed button (`element_id` or physical `x/y`), existing `audio_file` on the bridge host, optional `sent_marker` |
| `swipe` | `points`: 2–64 `{x,y}` entries; `duration_ms`: 1–5000, default 600 |
| `scroll` | `element_id` or `region:{left,top,right,bottom}`; `direction` |
| `input_text` | `observation_id`, `element_id`, `text`, `mode:replace/insert` |
| `back` / `home` / `recents` | No additional arguments |
| `launch_app` | `package_name` |

### Sending a voice message

Voice sending is optional on all Android versions supported by Phone Use. The experimental helper runs as ADB shell with Phone Use installed in the primary user profile. It checks for audio routing permission and a system API that can direct recording input to a specific app UID. Install the APK, start Phone Use and keep the following command running in a separate terminal (PowerShell or bash):

```sh
adb shell 'CLASSPATH=$(pm path ai.nidhoggfgg.phoneuse | head -n 1 | cut -c9-) app_process / ai.nidhoggfgg.phoneuse.voice.VoiceShell'
```

Wait for `Phone Use voice helper attached` and check `get_capabilities.send_voice.routing_api_available`. If false, see `unavailable_reason`. If true, the helper passed its initial checks; each send still verifies that the route works for the selected app. Observe the target app's hold-to-talk button. For CLI or stdio MCP, use these arguments with the observed IDs and a fresh request ID:

```json
{"request_id":"NEW_UUID","observation_id":"OBSERVATION_ID","element_id":"ELEMENT_ID","audio_file":"/absolute/path/message.wav"}
```

The bridge reads a mono PCM16 WAV at 8000–48000 Hz, up to 30 seconds. Direct HTTPS API/MCP clients send `pcm_base64` (signed little-endian PCM16 samples, without a WAV header) and `sample_rate` in place of the host path. Authenticated voice requests allow up to 4 MiB of JSON.

Phone Use prepares a UID-scoped recording route before pressing. It waits up to 2.5 seconds for a new, unsilenced recorder using that route, then releases after the playback head consumes the last frame. If the recorder disappears, the app window changes, or control is revoked while held, the helper attempts to cancel the gesture. If any touch may have occurred, inspect the conversation before deciding what to do next.

`execution.confirmation: released` confirms touch release. To verify sending, observe the conversation or provide an optional `sent_marker` that uniquely identifies this message's successful send. An exact visible text or description appearing in the same window within 1.5 seconds after release can return `sent_confirmed`. Omit the marker if the app exposes no reliable one. For pending requests, poll `get_status` using the original request ID. Restart the helper if Phone Use's process exits.

### Common action fields

Every input action from `tap` through `launch_app` requires `request_id` (up to 128 characters).

- `observation_id`: required for node/coordinate actions (`tap/long_press/send_voice/swipe/scroll/input_text`), optional for navigation and launch; associates device, display geometry, and target references.
- `expected_window_id`: optional expected active window.
- `observe_after`: default false; explicitly attaches a fresh observation.
- `observation_options`: the same parameters as `observe`, allowed only with `observe_after:true` or `observe_on_rejection:true`. It cannot contain action/routing fields such as `request_id` or `device_id`.
- `observe_on_rejection`: default false; attaches `recovery_observation` after a terminal target-validation rejection with confirmed `action_executed:false`. Pause, revocation, and stop restrictions still apply. Observation errors do not overwrite the action error.
- `lease_id`: optional test reservation; when reserved, both client and ID must match. Unreserved devices allow direct actions.

Every tool accepts `device_id` and `service_instance_id` guards checked before dispatch. The bridge adds them automatically; direct API clients may supply them. All tool results and nested action/observation results include both IDs. Device mismatch returns `DEVICE_MISMATCH`; service-instance mismatch returns `STALE_OBSERVATION`, both with `action_executed:false`.

Node and coordinate arguments cannot be mixed. Node actions use only explicitly supported operations, without coordinate fallback. Node scroll supports forward/backward; region scroll supports forward/down, backward/up, and left/right, referring to content browsing direction. Text is limited to 10000 characters; an empty string clears a field.

`input_text` revalidates the selected editor but does not implicitly focus, tap, read back, or wait. It returns `text_verified:false`; independently observe or explicitly request an attached observation. `replace` uses ACTION_SET_TEXT for the entire field. `insert` reads current text and selection, substitutes the selected range, writes the combined value with ACTION_SET_TEXT, and attempts to restore the insertion cursor. A node marked isShowingHintText keeps its hint but is treated as empty input. Readable empty text with selection (-1,-1) uses (0,0). Unknown selection for nonempty/unreadable text, or a password field, returns ACTION_UNSUPPORTED. Cursor restoration is optional: a node without ACTION_SET_SELECTION can still insert. `cursor_restored` reports Android acceptance; null means unnecessary, unsupported, or not attempted after cancellation. Rich editors and app-specific behavior need device testing.

## Observations and coordinates

Results include `device_id`, `service_instance_id`, `observation_id`, `ui_version`, `captured_at_ms` (Unix milliseconds), `window_id`, `package_name`, `display`, `tree`, `screenshot`, and `collection_attempts`.

- Coordinates are **physical pixels** on the default display at its current rotation, from top-left (0,0), including system bars and the keyboard. They are neither dp nor cropped app coordinates.
- JPEG uses quality 80 and defaults to a longest edge of 1280. Returned metadata includes width, height, `scale_x/scale_y`, and `offset_x/offset_y`. Convert with `screen_x = image_x * scale_x + offset_x`, likewise for y. Actions take screen coordinates.
- Rotation is 0/90/180/270. Rotation or display-geometry changes invalidate associated snapshots.
- Compact `tree.elements` filters invisible, empty, off-screen, and content/action-free nodes, keeping necessary structure. `parent_id` points to the nearest retained parent; `element_id` retains the original tree path, never an array index. Default states are omitted; readable editors include selection. `output:"legacy",compact:false` returns the full bounded tree and states; decision output still applies its projection rules. CLI and MCP use the same server output. Unnamed legacy Android actions use decimal IDs.
- Phone Use's pairing/authorization UI exports no tree content and rejects remote clicks/text (`LOCAL_CONTROL_UI`); navigation/launch can leave it. Only the default display is supported (`DISPLAY_UNSUPPORTED` otherwise).
- Each client retains at most eight snapshots for at most 30 seconds. Structural fingerprints have an estimated global 4 MiB budget; snapshots do not cache screenshots. Clients can evict only their own snapshots. Insufficient budget returns `BUSY`, `reason:snapshot_budget_exceeded` without evicting other clients' valid references. Element IDs are not permanent.
- Collection is bounded to 1500 nodes, depth 40, and 1024 characters per exported text/description. Password text is omitted. Fingerprints use digests of full fields, not truncated exports.
- Screenshot failure returns `screenshot.available=false` with a reason and Android code, independently of `tree.available`. Unrequested parts are null.
- Default observation collects immediately. Only explicit `stable_wait_ms>0` waits up to that duration for 120 ms of quiet; quiet does not prove business readiness. Clear window/app/display changes trigger bounded recollection. Ordinary content events, animation, and temporarily incomplete window inventories do not invalidate the whole observation. Unconditional collection uses at most `max_attempts`; conditional collection can run multiple rounds within its total budget. Only observations are repeated, never actions.
- Node actions find the candidate at the original path, checking identity, target fields, visibility, enabled state, and requested capability. They do not collect a full tree or compare ancestor layout/container subtrees. A replacement node at the same path is rejected. Changed text, bounds, or availability requires reselection. `replace` ignores selection-only changes; `insert` checks original text and selection.
- Coordinate actions check ownership, expiry, service instance, display size/rotation, and range. Ordinary refreshes, app changes, keyboard visibility, and cross-window paths do not invalidate all coordinates. Only explicit `expected_window_id` requires that active window. Phone Use windows and local notification controls remain protected.
- Sibling bounds, drawing order, and overlapping higher windows are not treated as proof of action failure. Clients choose nodes or coordinates using screenshots. System rejection is returned without retries, fallback, or automatic reselection.
- Errors preserve their code and add `error.reason` and available `snapshot_age_ms/snapshot_ui_version/current_ui_version/events`, distinguishing expiry, eviction, ownership/instance changes, display changes, and missing/replaced/changed targets. Pre-input rejection reports `action_executed:false`; uncertain input reports null.

UI events arrive asynchronously. Content can change between final validation and Android input; these checks do not eliminate all races.

### Conditional observation and total budget

Standalone `observe` and `observation_options` accept the same fields. `timeout_ms` is 1–60000, default 10000 with `wait_until`, otherwise 20000. It covers quiet waits, tree collection, screenshots, consistency recollection, and conditions. `stable_wait_ms` and `max_attempts` control each round, not the total budget. CLI/Web allow 90 seconds for standalone observation HTTP reads to accommodate queueing and return time. Disconnections do not retry input.

`wait_until` requires `package_name` or `element`; supplied fields combine with AND. Package matching confirms the current app, not page readiness. Element matching requires at least one exact `view_id`, `text`, or `description`; optional `visible`, `enabled`, `editable`, or `action` add constraints. Allowed actions: `tap`, `long_press`, `set_text`, `scroll_forward`, `scroll_backward`. Conditions do not accept `element_id`.

`element.match` defaults to `unique`: zero matches is unsatisfied; multiple matches is ambiguous. Use `exists` when any matching node suffices. Conditions run on the full bounded internal collection before output projection. If truncation or missing child reads prevents proving uniqueness, status is unknown. `collection_complete` and `collection_truncated` are independent; untruncated does not necessarily mean complete.

```json
{
  "tool":"launch_app",
  "arguments":{
    "request_id":"launch-001",
    "package_name":"com.example.shop",
    "observe_after":true,
    "observation_options":{
      "mode":"both","output":"decision","timeout_ms":10000,
      "wait_until":{
        "package_name":"com.example.shop",
        "element":{"view_id":"com.example.shop:id/search","visible":true,"enabled":true,"editable":true,"action":"set_text","match":"unique"}
      },
      "diagnostics":true
    }
  }
}
```

Result fields have separate meanings:

- `condition.status`: satisfied / unsatisfied / ambiguous / unknown; `timed_out` independently indicates budget exhaustion. May include `package_matched`, `match_count`, `match`, `element_ids`, `collection_truncated`, and `collection_complete`. Omitted when no condition was requested.
- `observation_status`: complete / condition_timeout / failed. Complete still requires checking requested `tree.available` and `screenshot.available`. Screenshot failure does not rewrite the action as failed.
- `inconsistency_detected`, `collection_attempts`: retain detected inconsistencies even if recollection succeeds.
- `failure_stage`: failure/timeout stage. Collection failure makes the current condition unknown. Optional `last_observation` retains its original timestamp and condition; historical conditions are not current conditions.

Timeout retains the last valid observation and its unsatisfied/ambiguous/unknown condition; it is not readiness. A later collection failure is not simply an unsatisfied condition. Treat action execution, observation availability, and condition satisfaction separately. A stable skeleton, empty tree, or duplicate target cannot satisfy a unique-target condition.

`condition.element_ids` explains internal matches. Actions may reference only nodes actually exported in that observation's `tree.elements`; screenshot-only or scoped output may omit matching nodes. Request a tree containing the target before a node action.

Tree and screenshot separately report `started_at_ms`, `finished_at_ms`, and `available`. Requesting one does not collect the other as a substitute. Screenshot conditions using element/subtree may collect nodes internally without exporting a tree. `elapsed_ms`, `wait_ms`, and `collection_ms` separate total, explicit wait, and collection processing time.

`diagnostics:true` adds collection timestamps and bounded `captures` with before/after windows, package, ui_version, timing, and `inconsistency_reason`. `capture_diagnostics_truncated` marks truncation; `consistency_scope` describes the checks. Tree/screenshot collection is not frame-atomic.

### Decision output and scope

`output` defaults to `decision`; explicitly use `legacy` for the older format. Decision projection keeps path IDs, necessary parents, content, bounds, supported actions, and states affecting interaction. Hint/error/state-description-only nodes may remain. Class, view ID, and unnamed actions are omitted by default; `include_details:true` adds `class`, `view_id`, and `raw_actions`. Unknown Android actions are not advertised as executable.

Decision output folds unambiguous static leaf labels into their nearest actual tap/long-press target, adding `label` and `label_sources`. The label is a presentation summary: it does not replace raw `text/description` or participate in exact `wait_until` matching. Source paths are evidence, not action references; actions still use the target's original exported `element_id`, bounds and supported actions. Independent controls, editors, stateful nodes and collection/collection_item groups remain. Folding never crosses semantic groups, scrolling regions or out-of-scope targets, and requires the target bounds to contain the text bounds. Labels are exactly deduplicated in tree order (description before text), with at most 8 source nodes and 1024 characters per summary; overflow nodes remain visible. `tree.merged_nodes` counts folded nodes. `include_details:true` unfolds the source nodes while retaining summaries; legacy output does not perform semantic folding. Obtain a new detailed observation before acting on a source node. Visual cards without accessibility grouping evidence are not assigned invented groups.

Interpret omitted states using `tree.state_defaults`: `enabled/visible` default true; `editable/password/focused/checkable/checked/selected/content_invalid` default false. Nodes report deviations; omission is not an opposite value or an absence of capability.

`region:{left,top,right,bottom}` keeps intersecting nodes in physical display pixels. `subtree:{observation_id,element_id}` locates and revalidates a subtree using this client's prior snapshot; an old path alone is insufficient. Both may constrain output together, but neither reduces internal collection nor crops the screenshot. All exported references retain observation/device/display/target checks.

`tree.collection_truncated` (compatibility alias `truncated`) means a collection bound was reached. `collection_complete` also accounts for missing child reads. `scope_clipped` means scoped projection was requested. `collected_nodes/output_nodes` count internal/exported nodes; `output_bytes` counts only the elements JSON's UTF-8 bytes. Client display truncation is separate. Smaller text output does not remove screenshot costs.

### Explicit focus before text input

If the screenshot shows an input area without an editable node, explicitly focus using current screenshot coordinates, then observe again. Replace all illustrative IDs, coordinates, and app identifiers below with actual observations:

1. `observe {"mode":"both","output":"legacy","compact":false,"include_details":true}`; retain the pre-focus tree and image.
2. `tap {"request_id":"focus-001","observation_id":"BEFORE_ID","x":320,"y":240,"observe_after":true,"observation_options":{"mode":"both","output":"legacy","compact":false,"wait_until":{"element":{"view_id":"com.example.shop:id/search","editable":true,"enabled":true,"action":"set_text"}},"timeout_ms":5000}}`.
3. Only after the new condition is satisfied and the tree confirms a unique editable node with set_text, use `input_text {"request_id":"type-001","observation_id":"AFTER_ID","element_id":"AFTER_ELEMENT_ID","text":"search text","mode":"replace"}`.

Do not infer editability from appearance. If the condition times out or the editor remains unclear, observe independently and decide again; do not replay focus or invent coordinate text input.

## Authorization, execution, and deduplication

Paired clients can submit input directly without acquiring control. Actions and immediate reads share one device queue (at most 32 queued requests); change waits do not occupy it. Queue admission allows up to 1000 ms to start, independently of operation duration. Requests that expire before starting return `BUSY` and never execute later. Expired actions retain a terminal result with `action_executed:false` under their request ID. Once started, operations keep their existing execution/observation limits. Reads require authentication.

Optional `acquire_device` reserves a device for up to five minutes for testing. A new reservation cannot be created while actions are unfinished. During it, input from another client or without the correct ID returns `DEVICE_RESERVED`. Old IDs after expiry/release return `LEASE_EXPIRED`. Validation happens at queue admission and before Android input, so expired queued actions do not execute. Reservations do not restrict reads or change pairing. Service destruction or local client revocation clears the reservation; injected input may finish. `get_status.reservation` reports state and remaining time without disclosing the ID.

Credentials remain valid until revoked. Revocation blocks that client’s new operations and cancels its queued input. Process/service restart does not replay requests and still requires authorization.

Actions execute once and return execution facts, without post-action trees/screenshots by default. `launch_app` additionally checks the active accessibility root package for up to five seconds; other actions do not add implicit waits. Missing observation is a normal success result. `observe_after:true` explicitly attaches observation; waiting must also be requested in `observation_options`. The client decides the sequence and may reuse a valid reference; observing after every action is not mandatory.

`launch_app` returns `execution.confirmation:"foreground_observed"` only after seeing the target package. A timeout returns `state:"unknown"`, `error.code:"RESULT_UNKNOWN"`, `error.reason:"launch_not_confirmed"`, and `action_executed:null`. The request was dispatched, so do not automatically replay it. Check the phone for a slow launch, a confirmation dialog, or background-launch restrictions. On every brand, Phone Use shows a local reminder and posts a separate app-launch notification, independent of the silent service notification and its pairing status. Tapping it opens the dashboard; repeated failures update one notification without repeatedly alerting while it remains posted. A confirmed launch or dismissing the in-app reminder clears it. Delivery follows the system notification permission and channel settings; blocked notifications do not replace the original launch result. Separately, the dashboard checks Xiaomi/Redmi/POCO background pop-up permission on resume and shows guidance when denied or a launch was not confirmed; unsupported checks remain unknown and do not cause a permanent dashboard reminder. Settings always provides an Other permissions shortcut, with app-details fallback. A permission flag does not confirm a launch, and an unconfirmed launch does not prove a permission is missing. Interruption after dispatch also stays unknown (`launch_confirmation_interrupted`); explicit Android permission denial returns `ACTION_REJECTED` (`launch_permission_denied`). No full tree or screenshot is collected for launch confirmation.

Actions wait synchronously for up to 15 seconds, then return `accepted` (queued) or `executing`. Continue querying `get_status`; neither means success. Executed input still waiting on observation reports `state:"executing"`, `action_executed:true`, `observation_status:"observing"` at the top level and in `get_status.request`. `observation_purpose` distinguishes after_action / after_rejection. These observation fields are omitted if none was requested. `accepted` with `action_executed:false` means not executed yet, not a terminal rejection.

```json
{
  "state":"executed",
  "action_executed":true,
  "request_id":"tap-001",
  "execution":{"confirmation":"android_accepted","business_success":null}
}
```

Terminal states:

- `executed`: Android accepted a node/navigation action, reported gesture completion, or the requested launch package was observed in the foreground; business success is not claimed.
- `failed`: validation or explicit system rejection, with `error.code/message`.
- `cancelled`: unexecuted input was cancelled or authorization/control became invalid.
- `unknown`: unconfirmed app launch, gesture callback timeout, partial cancellation, or an exception leaves execution uncertain. Re-observe; do not automatically retry.

Post-action observation failure/timeout preserves `executed`; observation reports its own state/error. An unmet condition must block steps depending on it. Cancellation cannot retroactively revoke injected input. `cancel` reports `in_flight_may_complete`; immediate gesture termination is not guaranteed.

`recovery` is advisory: `category`, `advisory:true`, `replay_action:false`, `suggested_tool`, and `message`. Pending requests use `wait_original_request`; unknown uses `reconcile_result`; terminal unexecuted target errors and post-action observation failures/timeouts use `refresh_observation`. Determine terminal state before replacing an action; `action_executed:false` alone is insufficient. Recovery observations use `recovery_observation` and may nest `last_observation`. CLI/Web identify image provenance recursively; historical images do not change current conditions/errors.

Window inventory is optional diagnostics (`diagnostics:true`), not a requirement that the app fill the screen or that clients select the keyboard window. Versions and event history explain changes without imposing whole-page stability.

Deduplication scope is **client_id + request_id in the current service process**. Tool and arguments must match exactly, ignoring object-key order. Results live for ten minutes, at most 256 IDs, with an approximately eight-million-character cache budget. A full cache rejects new actions instead of dropping unexpired IDs. Snapshots, images, and action arguments are memory-only and do not survive service stop/process death. The app writes no action log or screenshot files. Only the last unconfirmed launch package is retained locally for the permission reminder; a confirmed launch or local dismissal clears it.

If attached observation exceeds retention budget after execution, it becomes `observation_status:"failed"`, `error.code:"CACHE_BUDGET_EXCEEDED"`, `omitted_for_retention:true`; original action state, execution fact, and error remain. Never replay input because of this.

If remaining diagnostics are too large, `diagnostics_omitted_for_retention:true` removes detailed window/events and execution extras while preserving error code/reason, age/version, and confirmation. Execution marks `details_omitted_for_retention:true`; long platform errors may be shortened with `message_truncated_for_retention:true`. These cuts do not change execution facts or alone turn an observation into a failure.

An identical ID returns its cached result without new input or observation; different arguments return `REQUEST_ID_CONFLICT`. Cached observation timestamps/expiry do not refresh. Use standalone observe for new data. After ten minutes or process restart, deduplication cannot guarantee safe replay; clients must retain uncertain requests and reconcile with fresh observation.

The service starts automatically when the app opens. Notifications show status and open the app; there are no pause/resume or stop actions. Revocation rejects that client's subsequent reads/writes and cancels its queued input without affecting others. Android service destruction closes the endpoint and invalidates all unexecuted input.

## Main error codes

`INVALID_ARGUMENT`, `UNKNOWN_TOOL`, `UNAUTHORIZED`, `STOPPED`, `CANCELLED`, `BUSY`, `DEVICE_MISMATCH`, `DEVICE_RESERVED`, `LEASE_EXPIRED`, `REQUEST_ID_CONFLICT`, `REQUEST_NOT_FOUND`, `ACCESSIBILITY_UNAVAILABLE`, `SCREEN_OFF`, `DEVICE_LOCKED`, `NO_ACTIVE_WINDOW`, `OBSERVATION_INCONSISTENT`, `OBSERVATION_TIMEOUT`, `STALE_OBSERVATION`, `STALE_ELEMENT`, `WINDOW_MISMATCH`, `ELEMENT_UNAVAILABLE`, `ACTION_UNSUPPORTED`, `ACTION_REJECTED`, `APP_UNAVAILABLE`, `RESULT_UNKNOWN`.

`OBSERVATION_INCONSISTENT` describes collection changes, `STALE_OBSERVATION` invalid action context, and `STALE_ELEMENT` a changed target. These remain distinct for diagnostics and batch summaries.

## MCP

`POST /mcp` uses JSON-RPC 2.0 and supports initialize, ping, tools/list, tools/call, and notifications without responses. JSON-RPC batches are unsupported. Tools use the same execution code as the API. `notifications/initialized` returns HTTP 202; notifications never trigger tool actions.

Streamable HTTP uses stateless POST/JSON, without `Mcp-Session-Id`. GET/DELETE return 405; there is no SSE. Supported versions are 2025-03-26, 2025-06-18, and 2025-11-25; absent headers support older compatibility. The 2026-07-28 protocol without initialize is not implemented.

`tools/call` returns text blocks and separate image blocks for screenshots, including those nested in `request`, `observation`, `recovery_observation`, and `last_observation`. Text retains mapping, provenance, and original timestamps but removes duplicate `data_base64`. `isError` reports tool errors, observation failure, unmet conditions, or unavailable requested tree/image, while preserving action execution facts. Pending `observing` is not itself an error; query the original request. JSON-RPC `id` is not an action deduplication ID: `arguments.request_id` is still required. Use the `cancel` tool; generic JSON-RPC cancellation notifications do not directly revoke input.

### Bridge batch testing

`phoneuse_batch` accepts 1–100 explicit calls:

```json
{
  "calls": [
    {"device_id":"DEVICE_A","tool":"get_capabilities"},
    {"device_id":"DEVICE_B","tool":"get_capabilities"},
    {"device_id":"DEVICE_A","tool":"observe","arguments":{"mode":"tree"}}
  ],
  "summary_only": false,
  "continue_on_error": false
}
```

Execution is sequential per device and parallel across devices; results retain input order. Supply `lease_id` in each entry's arguments or at its top level. By default, failure, unknown/pending execution, unsatisfied/ambiguous/unknown conditions, unavailable requested tree/image, or missing requested attached observation skips later calls on that device. Other devices continue. `get_status` is evaluated through its nested `request`; historical `last_observation` cannot make a failed current observation ready. `continue_on_error` permits independent tests to continue, without retrying sent actions.

`summary` includes `total`, `succeeded`, `failed`, `pending`, `unknown`, `skipped`, `states`, `error_codes`, `observation_error_codes`, `observation_incomplete`, `observation_statuses`, and `condition_statuses`. Executed actions with failed observations retain the compatibility `succeeded` count and also increment `observation_incomplete`; succeeded is not a business success rate. Condition counts use current/recovery observations, not historical conditions. Observation errors include nested recovery results. MCP sets `isError` when observation_incomplete is nonzero.

`summary_only` omits individual results. Batch output excludes screenshot base64 at every depth. CLI `batch @calls.json` reads the calls array; `--summary-only` and `--continue-on-error` correspond to these options. `call ... --screenshot-out PATH` saves the newest available image from nested requests/recovery/history and reports `screenshot_output_source` and `saved_path`. Image-save failure does not erase action results.

## Implementation references

- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 17 local network permission](https://developer.android.com/about/versions/17/behavior-changes-17)
- [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
