# Development and validation

**English** | [简体中文](validation.zh-CN.md)

## Build from source

Use JDK 25 and Android SDK 37 with the checked-in Gradle wrapper. Configure the SDK locally; do not commit machine paths.

```sh
git clone --recurse-submodules https://github.com/nidhoggfgg/PhoneUse.git
cd PhoneUse
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat :app:assembleDebug`. Install `app/build/outputs/apk/debug/app-debug.apk` on Android 11+. The build bundles the client, API references, and Skill instructions into the APK. For an existing checkout, initialize or update the Skill with `git submodule update --init --recursive` before building.

## Automated checks

```sh
git submodule update --init --recursive
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -m py_compile tools/phoneuse_client.py
python tools/sync_skill.py --check
python -m unittest discover -s tools -p "test_*.py"
npm test
npm pack --dry-run
python tools/export_integrations.py
```

On Windows use `.\gradlew.bat` instead of `./gradlew`. Python checks require Python 3.10+; launcher tests require Node.js. The web-console suite additionally needs Playwright and its Chromium browser:

```sh
node tools/test_web_console.cjs
```

To use installed Microsoft Edge, set `PHONEUSE_BROWSER_CHANNEL=msedge`. This suite uses simulated phone HTTP responses; it does not prove physical-device behavior.

## Emulator tests

Use a dedicated emulator. The fixture and instrumentation tests can temporarily alter accessibility settings and operate the foreground UI. Do not point them at a phone in active use.

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

The Gradle task installs the debug-only fixture first. Test reports are written to `app/build/reports/androidTests/connected/debug/`. See the [fixture guide](../fixture/README.md) for individual scenarios and targeted runs. Settings are restored at normal completion; an interrupted test may require manual restoration.

## What the evidence covers

| Layer | Coverage | Limits |
| --- | --- | --- |
| JVM tests | Authorization, queueing, request IDs, cancellation, observation and target validation logic | Fake device executors do not validate Android input |
| Python / launcher tests | Pairing, device routing, failures, MCP, configuration, portable packages | Simulated endpoints do not replace multi-phone testing |
| Web tests | Pairing UI, recovery, API forms, request IDs, responsive layout | Simulated HTTP responses |
| Android instrumentation | Production service, accessibility, fixture controls, UI language and persistence | Emulator coverage does not establish vendor compatibility |

## Physical-device acceptance checklist

Record device model, Android version, app revision, latency, error codes, and recovery steps. Android 11–12 language persistence, Android 14+, Android 17+ permissions, and vendor background restrictions still need matching-device verification.

1. Install, grant accessibility and notifications, verify automatic service startup, and verify local-network permission where applicable. Pair from the web console and MCP; compare verification codes on both ends. Test approval, denial, expiry, and revocation.
2. Verify unauthenticated API/MCP requests fail, browser requests obey Host/Origin checks, and the root page remains accessible. Pairing must not enable remote approval of Phone Use's protected controls.
3. Observe tree, screenshot, and both modes. Compare bounds and coordinates across system bars, keyboard visibility, and rotation. Check protected screenshots and password fields independently of tree availability.
4. Launch an app, focus a field explicitly, observe it, replace and insert Chinese text, then independently verify the actual text and selection. Test empty fields and unsupported editors.
5. Test node and coordinate taps, long presses, scrolling, full swipe paths, and Back/Home/Recents. Confirm actual target behavior and that no fallback or repeated input occurs.
6. Change a selected target, its identity, display geometry, and service instance. Stale references must fail. Ordinary unrelated refreshes must not impose whole-page stability. Test explicit `expected_window_id` separately.
7. Repeat an identical `request_id`: only one input should occur. Reuse the ID with different arguments: expect a conflict. For network loss, query the original request; never infer safe replay after retention expiry or process restart.
8. Queue actions and revoke the client locally. Its queued inputs must not run; an injected gesture may finish. Revoking one client must not invalidate another client's queue. Service destruction must cancel all unexecuted input.
9. Confirm default actions do not collect post-action trees/screenshots. For `launch_app`, test foreground confirmation, a five-second timeout with `RESULT_UNKNOWN`, permission guidance, and cancellation after Intent dispatch. Other actions do not implicitly wait. Test explicit conditions, ambiguous targets, timeouts, and successful input followed by observation failure. Verify execution facts remain separate from observation and business success.
10. Open the app after a fresh install and after saving legacy stopped/paused preferences; verify automatic port 8443 startup in both cases. Reopen after service destruction and verify startup and saved pairings. The dashboard and notification must have no start, pause, or stop controls. Background keep-alive defaults on. Turning it off persists across recreation, preserves sessions while switching apps, stops control when removing the recent task, and prevents boot/update/accessibility reconnection recovery. Re-enable and verify task removal, system reclamation, and boot recovery. Force-stop still requires reopening the app. Check battery status refresh, OEM settings fallbacks, and English/Chinese layouts; vendor autostart and battery policies need physical-device validation.
11. Connect a physical phone and emulator to one bridge. Verify explicit IDs, independent calls, one-device outage, and refusal to send old credentials when an address points to another installation. Cross-client and cross-instance observation references must fail.
12. Run dynamic lists, repeated screenshots, keyboard interactions, overlays, and at least ten minutes of background operation. Record both failures and recovery; do not generalize emulator results to all phones.

## Documentation checks

Keep English and Simplified Chinese pages paired and linked. API names, JSON keys, command flags, and example IDs stay unchanged between languages. Run Skill synchronization and package checks after changing API references or distributed guides. Do not turn historical passing counts into current-run claims.
