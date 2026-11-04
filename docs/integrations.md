# Connect Phone Use to an AI client

**English** | [简体中文](integrations.zh-CN.md)

The recommended route is to send your AI the prompt provided by the app to install the Skill directly from the phone and connect. GitHub Skill installation and MCP remain available.

## Install directly from the phone (recommended)

1. Open the app, enable accessibility, and ensure the AI's execution environment can reach the phone.
2. In **Connect your AI**, choose a phone address reachable by the AI.
3. Use the copy button beside the prompt and send the complete text to the AI. It includes connection instructions and a link like `https://PHONE:8443/connect#sha256=PACKAGE_HASH`.
4. The AI reads the guide, downloads the complete Skill from the same port, checks the SHA-256 from your original link, and installs it in its host's Skill directory. A host without persistent Skill installation can extract it into a workspace and run the client directly.
5. Compare all eight digits shown by the AI and phone, approve on the phone, and explicitly confirm the match to the AI. Later connections reuse the saved pairing.

This route uses **Python 3.10+ and its standard library**. The AI must have file access, program execution, and network access to the phone; a URL cannot grant these capabilities to a cloud-only chat. Hosts that discover new Skills only in a new session can still read SKILL.md and run the client in the current session.

The package matches the installed app. After an app update, copy a fresh link and ask the AI to update its Skill. Stop on a hash mismatch; preserve customized files as a backup and keep `~/.phoneuse.json`. Initial downloads use self-signed TLS: only public, credential-free downloads may skip certificate-chain validation, with the hash from the user's link checked before execution. Pairing and control retain the client's strict public-key checks. A hash fetched from the same unverified server alone checks integrity, not authenticity.

Public routes are `GET /connect`, `GET /skills/manifest.json`, `GET /skills/phone-use.zip`, and `GET /skills/phone-use/SKILL.md`. Existing host allowlist and HTTP settings apply. Downloads do not require pairing; device control still does.

## Requirements for GitHub installation

Both integrations need Python 3.10+. For online installation, use Node.js 22.20+ with npm/npx and Git. The MCP launcher itself supports Node.js 18+. Start the service on the phone, enable accessibility for device operations, and make sure the client can reach the phone's IPv4 address. Initial pairing requires approval on the phone. Transport is pinned HTTPS; compare all eight digits with the phone, approve there, and explicitly confirm the match to the AI before it passes `confirm_pairing:true`. There is no cloud relay.

The Skill commands fetch the standalone `nidhoggfgg/phone-use-skills` repository; MCP commands fetch `nidhoggfgg/PhoneUse`. Both use the default branch, and the corresponding repository must be accessible. `@nidhoggfgg/phone-use` is the package's internal name, not a claim that it is published to the npm registry.

## Option 1: Agent Skill

```sh
npx skills add nidhoggfgg/phone-use-skills --skill phone-use
```

Choose your AI client and project or global scope. The installation includes instructions, the Python client, and API references. No Android build or separate MCP installation is needed on the client machine.

For a global Codex installation:

```sh
npx --yes skills add nidhoggfgg/phone-use-skills --skill phone-use --agent codex --global --yes
```

The first `--yes` lets npx fetch the installer; the second skips installer prompts. Omit `--global` for project scope. Other clients can be selected interactively. Reload Skills, then ask:

> Use $phone-use to connect to https://192.168.1.20:8443. Show me the verification code, wait for my approval on the phone, then inspect the current screen.

An installation request you can give an agent:

> Install the phone-use Skill from https://github.com/nidhoggfgg/phone-use-skills for this AI client, using npx skills add with project scope. Check Python 3.10+ and preserve existing Skills. Then connect to my phone at https://PHONE_IPV4:8443; I will approve pairing on the phone.

Replace the example address. The agent should ask for it if unknown. Installing a Skill does not authorize access to a phone.

## Option 2: MCP

Configure the AI host to launch:

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

The host normally owns this process. Manually running it may appear idle because it is waiting for JSON-RPC on stdin. The launcher finds Python 3.10+ automatically. To choose an interpreter, set `PHONEUSE_PYTHON` to its absolute path, without arguments.

### Register with Codex

macOS / Linux:

```sh
codex mcp add phone-use -- npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

Windows:

```powershell
codex mcp add phone-use -- cmd /d /c npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

Without the Codex CLI, or to configure timeouts, generate a snippet:

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use print-config --host codex
```

Merge the generated `[mcp_servers.phone-use]` section into `~/.codex/config.toml`, keeping only one section with that name and preserving other settings. It sets startup and tool timeouts to 120 seconds. Running `print-config` first also lets npm cache the package before the host starts it.

### Other MCP clients

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use print-config
```

This generates platform-specific `mcpServers` JSON, including the Windows `cmd` invocation. Merge the `phone-use` entry into a compatible host configuration; do not overwrite unrelated services. Hosts using other formats need the equivalent settings in their own format. npx manages the downloaded paths.

An installation request you can give an agent:

> Install Phone Use MCP for this AI client as phone-use, using npx -y --package=github:nidhoggfgg/PhoneUse phone-use. Check Node.js, Git, and Python 3.10+. Prefer the host's MCP registration command; otherwise generate a snippet with print-config and merge it without removing other services. On Windows, launch npx through cmd /d /c. Connect to https://PHONE_IPV4:8443; I will approve pairing on the phone.

### First connection

Reload the host's MCP configuration. Ask the agent to call:

```json
{"address":"https://192.168.1.20:8443"}
```

with `phoneuse_connect`. Compare all eight digits and approve on the phone, then explicitly tell the AI the codes match. Only then call `phoneuse_connect` with `{"device_id":"RETURNED_DEVICE_ID","confirm_pairing":true}` to finish claiming authorization. Every subsequent device call must explicitly include `device_id`.

Optional startup arguments are `--url https://PHONE_IPV4:8443`, `--name "My AI"`, and `--config /absolute/path/registry.json`. Pass them as separate host arguments; Windows `cmd` requires its own quoting rules. Ordinary setup can omit them and supply the address through `phoneuse_connect`.

The bridge supports MCP `2025-03-26`, `2025-06-18`, and `2025-11-25`, and returns screenshots as native image blocks. It can initialize while the phone is offline. After pairing, `notifications/tools/list_changed` announces device tools. If the host does not refresh, use `phoneuse_call` with `device_id`, `tool`, and `arguments`. Long batches may need a longer host timeout or smaller batches. The 2026 protocol is not implemented.

### Local checkout or direct HTTP

A checkout can run `python /absolute/path/tools/phoneuse_client.py mcp-stdio --url https://PHONE_IPV4:8443 --name "My AI"` as the host's stdio command.

For programs that manage their own Bearer credentials, the phone also exposes `https://PHONE_IPV4:8443/mcp`. It supports Streamable HTTP POST/JSON without SSE. See the API reference in the repository for native pairing and transport details.

## Updates and credentials

Rerun the Skill installation command to update it. npm may cache GitHub default-branch packages. For reproducible MCP installs, replace `github:nidhoggfgg/PhoneUse` with `github:nidhoggfgg/PhoneUse#REF`, using an actual tag or commit. Change the reference and restart the host to upgrade; do not assume a release tag exists.

MCP and Skill share `~/.phoneuse.json` by default. npm cache changes do not remove phone authorization. Use separate `--config` paths for separate credentials. Never commit or distribute the registry. Access can be revoked on the phone.

A cloud host without local execution or a route to your phone cannot use this bridge directly. If tools are missing, check stdio MCP support. If actions fail, check accessibility, lock state, and client authorization. After a network interruption, query the original `request_id`; do not replay uncertain input.

## Maintenance and offline packages

`skills/` is a Git submodule pointing to [phone-use-skills](https://github.com/nidhoggfgg/phone-use-skills); `skills/phone-use/` is self-contained. Initialize it after cloning or pulling the main repository:

```sh
git submodule update --init --recursive
```

For a fresh clone, use `git clone --recurse-submodules https://github.com/nidhoggfgg/PhoneUse.git`. After initialization, run `git -C skills switch main` before editing the Skill. The canonical client and API documents live at `tools/phoneuse_client.py`, `docs/api.md`, and `docs/api.zh-CN.md`. After changing them:

```sh
python tools/sync_skill.py
python tools/sync_skill.py --check
python -m unittest discover -s tools -p "test_*.py"
npm test
npm pack --dry-run
```

Commit and push the synchronized files in the skills repository first, then commit the updated `skills` pointer together with the source changes in the main repository:

```sh
git -C skills add phone-use
git -C skills commit -m "Update phone-use Skill"
git -C skills push origin main
git add skills
```

Stage the corresponding source changes before committing the main repository. CI checks out the pinned submodule commit and checks for stale copies. The submodule uses HTTPS for public clones; maintainers can configure SSH pushes with `git -C skills remote set-url --push origin git@github.com:nidhoggfgg/phone-use-skills.git`. npm uses an explicit file list and has no install-time build script. It excludes the Android project, credentials, and screenshots.

`python tools/export_integrations.py` creates `dist/integrations/phoneuse-mcp.zip`, `phone-use.zip`, bilingual installation guides, and checksums. Offline MCP uses `python configure.py --host json` or `--host codex` to generate absolute paths; regenerate after moving the directory.

Format references: [Skills CLI](https://github.com/vercel-labs/skills#readme), [npm exec / npx](https://docs.npmjs.com/cli/v11/commands/npm-exec/), [Codex MCP](https://developers.openai.com/codex/mcp).
