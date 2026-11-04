# Contributing to Phone Use

**English** | [简体中文](CONTRIBUTING.zh-CN.md)

## Report a problem

Include the app revision, device model and Android version, client/host version, steps to reproduce, expected and actual behavior, and relevant error codes. Distinguish emulator results from physical-device results. For uncertain input, report the original request state without replaying it just to reproduce the issue.

Remove credentials, pairing secrets, personal screen contents, and machine-specific paths from reports. Do not attach `~/.phoneuse.json` or real account data. This project uses the [MIT License](LICENSE).

For a suspected vulnerability, follow the private reporting process in the [security policy](SECURITY.md) instead of opening a detailed public issue.

## Make a change

Initialize the Skills submodule with `git submodule update --init --recursive` after cloning or pulling. Before editing it, run `git -C skills switch main`. Push Skills changes to [phone-use-skills](https://github.com/nidhoggfgg/phone-use-skills) first, then commit the updated `skills` pointer in this repository. See the [maintenance workflow](docs/integrations.md#maintenance-and-offline-packages).

Keep changes focused and explain the problem and resulting behavior. Add or update tests when behavior changes, especially authorization, request deduplication, target validation, or execution/observation states. Use a dedicated emulator for tests that alter accessibility settings or operate the screen. See [development and validation](docs/validation.md) for commands and coverage limits.

In a pull request, describe what changed, how you checked it, and what remains unverified. Separate Android accepting an action from the app task actually succeeding. Do not claim physical-device coverage from fake executors or simulated HTTP endpoints.

## Documentation and translations

- English is the default in `README.md` and ordinary guide paths. Simplified Chinese uses sibling `*.zh-CN.md` files. Put reciprocal language links below each title and keep internal navigation in the selected language.
- Update both versions when changing behavior or setup. Preserve API/tool names, JSON keys, flags, paths, and example identifiers.
- `docs/api.md` and `docs/api.zh-CN.md` are the canonical protocol references. Run `python tools/sync_skill.py` after editing them, and commit their generated copies under `skills/phone-use/references/` in the submodule before updating the main repository pointer.
- `skills/phone-use/SKILL.md` is the English machine-facing entry point. Its Chinese companion is `README.zh-CN.md`, without Skill frontmatter, so installers do not discover duplicate Skills.
- When adding distributed documentation, update `tools/export_integrations.py` and the npm `files` list as needed. Check both repository and extracted-package links.

## Source map

| Path | Purpose |
| --- | --- |
| `app/` | Android app, accessibility service, HTTP/MCP service, web console |
| `tools/phoneuse_client.py` | Canonical Python CLI and stdio bridge |
| `bin/phone-use.mjs` | Node launcher and host configuration output |
| `skills/` | Git submodule of `phone-use-skills`; contains the self-contained `phone-use/` Skill |
| `integrations/mcp/` | Offline MCP configuration generator and guides |
| `fixture/` | Debug-only Android test scenarios |
| `docs/` | Usage, integration, protocol, and validation guides |
