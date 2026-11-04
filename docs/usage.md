# Usage and connections

**English** | [简体中文](usage.zh-CN.md)

## Connect your AI

1. Download the APK from [GitHub Releases](https://github.com/nidhoggfgg/PhoneUse/releases/latest), install it on Android 11+, and open Phone Use.
2. Enable Accessibility and allow notifications and local-network access when requested. Keep the phone awake and unlocked. Your agent's computer needs Python 3.10+ and network access to the phone.
3. In **Connect your AI**, choose a reachable phone address, copy the complete prompt, and send it to your agent. It installs the Skill and starts pairing. Compare all eight digits shown by the agent and phone, approve on the phone, then tell the agent you confirmed the match.

You can now ask the agent to operate your phone. Completed pairings survive restarts. Manual Skill installation, MCP, and updates are covered in the [AI integration guide](integrations.md).

## Permissions and background app launches

Some Android versions require allowing **restricted settings** in the system's app details before enabling Accessibility for a sideloaded APK. Allow notifications when prompted; Android 17+ also requests local-network permission.

On Xiaomi/Redmi/POCO, Phone Use checks the background pop-up permission when the screen resumes. The dashboard shows **Background app launches** when the permission is denied or a launch was not confirmed. If the firmware does not support checking, the dashboard only shows a reminder after an unconfirmed launch. **Settings → Background app launches** always provides a manual entry to system permissions. Allow background pop-up windows there; opening the settings page does not grant permission.

On every brand, a denied or unconfirmed launch also posts a notification that opens Phone Use. Repeated failures update one notification; a confirmed launch or dismissing the in-app reminder clears it. Check the phone for app-opening dialogs or loading delays before retrying. If the service stops when you leave the app, see [background keep-alive](#service-and-pairing-persistence).

## Web console

The app's **Open web console** button always opens `http://127.0.0.1:8443/`. The button is disabled when a configured access address allowlist omits `127.0.0.1`; add it or clear the list to restore access. Only local access through `127.0.0.1` allows HTTP without the insecure-HTTP switch; address checks and pairing still apply. Remote browser access defaults to `https://192.168.1.20:8443/` and requires independently established certificate trust. Its six-digit authorization code does not replace TLS authentication. The console supports API forms, JSON editing and explicit screenshots.

Settings → **Allow insecure HTTP connections** enables other HTTP access on the same port, including LAN addresses, domains and IPv6 loopback. Adding an address to the allowlist does not enable HTTP, and this HTTP switch does not bypass the allowlist. It is off by default, persists across restarts, and takes effect immediately. Turning it off also disconnects existing HTTP requests that depend on the switch. HTTP does not encrypt screen content, actions, or credentials. Pairing and authorization remain required. HTTPS and HTTP have separate browser cookies; switching schemes may require pairing again.

Settings → **Access address allowlist** accepts one IP address or domain per line, without a scheme, port, path, or wildcard. An empty list allows only the receiving IP of the phone, including loopback, and rejects domains. A nonempty list replaces that default: only listed destination addresses are accepted, including for the local console. These are addresses used to reach the phone, not client source IPs. Saving applies to subsequent requests without restarting the service; clearing the list restores the default. Domains match exactly, without automatically allowing subdomains. An existing public-address setting migrates to a single allowlist entry, so add any phone IPs you still need.

The dashboard shows only listed addresses when an allowlist is configured; otherwise it shows the phone's non-loopback network addresses. HTTPS URLs are shown by default, with HTTP URLs added when enabled. Listing an address does not make it reachable: DNS, routing and port forwarding to port 8443 remain the deployment's responsibility. Pairing and authorization are still required.

The browser stores authorization in an HttpOnly cookie. Closing a tab, restarting the browser, or restarting the app does not normally require pairing again. Revoke access on the phone or use the console's unpair action. Clearing cookies or changing the browser address creates a new pairing context. Long-term tokens are not displayed or exported by the console.

For actions, retain the same `request_id` when querying or retrying the same request. Generate a new ID only for a new action. Connection recovery does not replay inputs.

## Python client

Python 3.10+ is required; no third-party libraries are needed. Run from the repository root:

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --full-tree
```

`pair` displays a locally computed eight-digit code. Compare every digit with the phone, approve there, then type `yes` in the CLI. With MCP, tell the AI you verified the match before it calls `phoneuse_connect` with `confirm_pairing:true`. Completed pairings reconnect automatically. Pending requests expire after two minutes and are cleared by a phone-service restart.

The default registry is `~/.phoneuse.json`. Use `--config /absolute/path/registry.json` **before the subcommand** for a different location. Always pass the returned `DEVICE_ID`, including when only one device is connected. Update an address with `pair --url NEW_URL --device-id DEVICE_ID`. The bridge checks installation identity before sending stored credentials; an old configuration without a trusted device ID requires new approval.

### JSON arguments and actions

For portable quoting, save arguments as a UTF-8 file named `action.json`:

```json
{"request_id":"home-001"}
```

```sh
python tools/phoneuse_client.py call home @action.json --device-id DEVICE_ID
python tools/phoneuse_client.py call get_status @action.json --device-id DEVICE_ID
```

The ID above is an example. Use a new unique ID for each new input action. After a network error, query the original ID; do not automatically submit the action under another ID. Node actions require `observation_id` and `element_id` from an observation. Coordinate actions also require `observation_id`; map screenshot pixels to screen pixels using the returned scales and offsets.

The CLI omits image base64 by default, keeping image dimensions and coordinate mapping. `--screenshot-out PATH` saves the image; `--include-image-data` includes base64 explicitly. MCP returns native image content blocks. Full tree output uses `output:"legacy",compact:false`, or CLI `--full-tree`.

### Batches and optional reservations

`batch @calls.json` and MCP `phoneuse_batch` accept explicit calls, each with `device_id`, `tool`, and `arguments`. Calls execute in order on each device and independently across devices. By default, a failed or unfinished step stops subsequent calls on that device. `--summary-only` returns aggregate results. Batches do not plan tasks, fill in observation IDs, or retry actions.

For controlled tests, `acquire_device` creates a time-limited reservation. Pass its `lease_id` with actions and call `release_device` when finished. Normal actions need no reservation when the device is unreserved. Client access can always be revoked on the phone. See the [API reference](api.md) for execution states and retention limits.

## Service and pairing persistence

Opening the app automatically starts the service on port 8443. The dashboard and notification show service status without start, pause, or stop controls. Older saved pause or stop settings are ignored. Completed pairings remain, but inputs are never automatically replayed.

**Settings → Background keep-alive** is on by default. It retains the foreground service and persistent notification, permits recovery after system reclamation, and attempts restoration after boot and unlock, an app update, or a system accessibility reconnection. Turning it off disables automatic recovery and stops the control service when you remove Phone Use from recent apps. Switching to another app keeps the current session running; reopening Phone Use starts a new session. This setting does not clear pairings, revoke accessibility access, or change existing system permissions.

Use **Battery optimization** to approve an exemption; the status refreshes when you return. **Autostart settings** tries the vendor page and falls back to app details. Xiaomi/Redmi/POCO users should allow autostart, choose No restrictions for battery saver, and lock the recent task. Huawei/Honor users can manually allow auto-launch, secondary launch, and background activity. OPPO/realme/OnePlus/vivo users should check autostart and background limits; Samsung users can add Phone Use to Never sleeping apps. Vendor autostart grants have no portable status API and require manual verification; opening a page does not mean permission was granted. Disabled service notifications also get a settings shortcut.

Keep-alive cannot override Force stop or guarantee survival under every vendor policy. Reopen the app after a force-stop. If accessibility was turned off, enable it again in system settings; the app cannot silently grant it. See [Tasker’s background guidance](https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html), [Android battery exemptions](https://developer.android.com/training/monitoring-device-state/doze-standby), and [foreground service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Deleting the client configuration, clearing app data, uninstalling the phone app, clearing browser cookies, or revoking access requires pairing again. A stable phone address simplifies browser access. MCP and Skill use the same registry by default; different `--config` paths keep their credentials separate.

## Network troubleshooting

The service accepts **HTTPS and HTTP on port 8443**. MCP/Python clients continue to require pinned HTTPS and never downgrade. Remote HTTP requires the explicit setting above. Host must match the receiving IP when the access address allowlist is empty, or an exact listed address when configured, always with the service port; Origin must exactly match the request scheme and authority. Forwarding headers cannot grant the local `127.0.0.1` HTTP exception. There is no cloud relay or automatic port mapping. See [Transport security](transport-security.md).

For optional local development forwarding with ADB:

```sh
adb forward tcp:8443 tcp:8443
```

Then connect with `python tools/phoneuse_client.py pair --url https://127.0.0.1:8443`. Normal network use does not require ADB.

Update both the phone and bridge. Existing HTTP addresses are migrated locally to HTTPS, but their old credentials are discarded; compare a new code and pair again. A changed TLS key is never automatically accepted. After independently verifying a deliberate reinstall/reset, revoke the old phone client and use `python tools/phoneuse_client.py forget-device --device-id DEVICE_ID` before re-pairing.

If input is unavailable, check accessibility, screen lock, and client authorization. If the AI host runs in the cloud and cannot reach your LAN or run a local bridge, installing the integration alone will not provide access.

## Languages and device names

App settings offer System default, 中文, and English. The selection persists on Android 11+ and integrates with Android's app-language settings on Android 13+. Notifications follow the selection. Changing language does not reset the service or pairings. Android itself controls how the accessibility-service description is displayed in system settings.

The default device name uses the system device name where available, then brand and model; emulators use a readable fallback. Vendor APIs may return model codes rather than marketing names. A custom name takes precedence and changes neither the system device name nor installation ID.

For translations, see `app/src/main/res/values/strings.xml`, `values-zh/strings.xml`, and `res/xml/locales_config.xml`. Preserve formatting placeholders and plurals. Web strings live in `app/src/main/assets/web/i18n.js`; language changes take effect after refresh. API names, parameter keys, raw responses, and server tool descriptions keep their protocol spelling.
