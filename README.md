<div align="center">
  <img src="design/icon/phone-use-round.png" width="80" alt="Phone Use">
  <h1>Phone Use</h1>
  <p><strong>Give AI agents control of your Android phone.</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
    <a href="docs/integrations.md"><img src="https://img.shields.io/badge/Agent-Skill%20%7C%20MCP-7963D2" alt="Agent Skill and MCP"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue" alt="MIT License"></a>
  </p>
  <p><a href="https://github.com/nidhoggfgg/PhoneUse/releases/latest"><strong>Download APK</strong></a> · <a href="#quick-start">Quick start</a> · <a href="#examples">Examples</a> · <a href="docs/usage.md">Docs</a></p>
  <p><strong>English</strong> | <a href="README.zh-CN.md">简体中文</a></p>
</div>

Phone Use connects your existing AI agent to your phone through **Skill or MCP**. The agent can read the screen, tap, swipe, type, and switch apps—**no root, ADB, or per-app integration required**.

## Features & advantages

| Feature | How Phone Use helps |
| --- | --- |
| **No ADB required** | Compared with ADB-based control, everyday use only requires installing the app, enabling Accessibility, and pairing over the network. No USB debugging or cable is needed. |
| **Easy integration with AI agents** | Skill, MCP, Python CLI, and HTTP API interfaces connect agents that support these tools and fit into custom automation workflows, without tying you to a particular model or agent framework. |
| **Screenshots + an optimized UI tree** | Compared with screenshot-only control, agents can also read interface text, element states, and available actions directly, then act by element ID. Screenshots and coordinate actions also support games and custom-drawn interfaces. |
| **AI-friendly, with lower token overhead** | Compared with raw UI tree dumps, the default compact output filters out invisible nodes and nodes with no useful content or actions, and omits redundant state fields. Semantic folding, focused observations, and tree-only requests further reduce unnecessary information and token usage. |
| **HTTPS and pairing by default** | Remote connections use HTTPS encryption by default. Skill/MCP clients require an eight-digit code comparison on both ends and approval on the phone, then verify the pinned public key on subsequent connections. Revoke any client from the phone at any time. See [transport security](docs/transport-security.md). |
| **Built for multiple phones and larger workflows** | One bridge can manage multiple phones, with explicit device IDs, concurrent execution across devices, ordered execution per device, batch calls, and optional time-limited device reservations for automation and testing. |
| **Personal remote control, too** | Connect a single phone and let your own agent operate it, or call tools directly through the CLI or built-in Web console. The controller must be able to reach the phone's address; the project does not provide a cloud relay. |

## Examples

<table>
  <tr>
    <td width="50%" valign="top">
      <p><strong>Compare prices on Xianyu</strong><br>Collect 29 RAM offers and check item details.</p>
      <a href="docs/images/secondhand-shopping.webp"><img src="docs/images/secondhand-shopping.webp" width="100%" alt="AI browsing secondhand RAM listings on Xianyu and organizing prices by specification"></a>
    </td>
    <td width="50%" valign="top">
      <p><strong>Catch up on QQ messages</strong><br>Review recent chats and find messages worth a reply.</p>
      <a href="docs/images/message-review.webp"><img src="docs/images/message-review.webp" width="100%" alt="AI reviewing QQ conversations and summarizing messages that may need a reply"></a>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <p><strong>Play a roguelike</strong><br>Explore, fight, and clear the first floor as a mage.</p>
      <a href="docs/images/roguelike.webp"><img src="docs/images/roguelike.webp" width="100%" alt="AI playing a mage, reaching the second dungeon floor, and recapping the run"></a>
    </td>
    <td width="50%" valign="top">
      <p><strong>Discover people on Soul</strong><br>Follow profiles and summarize their interests.</p>
      <a href="docs/images/social-discovery.webp"><img src="docs/images/social-discovery.webp" width="100%" alt="AI browsing Soul profiles and summarizing the interests of accounts it followed"></a>
    </td>
  </tr>
</table>

## Quick start

You'll need an **Android 11+ phone** and an **AI agent that can run programs**, with **Python 3.10+** in its execution environment. Connect the phone and agent's computer to the same Wi-Fi, or another network where the phone is reachable.

1. **Install the app.** [Download the APK](https://github.com/nidhoggfgg/PhoneUse/releases/latest), install it on your phone, and open Phone Use.
2. **Grant permissions.** Enable **Accessibility** and allow notification and local-network permissions when prompted.
3. **Connect your agent.** In **Connect your AI**, select a reachable phone address, tap **Copy prompt**, and send the whole prompt to your agent. It installs the Skill and starts pairing.

For the first connection, compare the **eight-digit code** on your phone with the one shown by the agent. If they match, approve on the phone and tell the agent you've confirmed the match.

**Pair once, then just ask your agent to use your phone.** Pairings survive restarts; removing pairing data or revoking access requires pairing again. Keep the phone awake and unlocked during use.

### Manual Skill installation

With **Node.js 22.20+**, **Git**, and **Python 3.10+** installed:

```sh
npx skills add nidhoggfgg/phone-use-skills --skill phone-use
```

Choose your agent and installation scope, then send it the following, replacing the address with the **HTTPS address shown in the app**:

> Use the phone-use Skill to connect to https://192.168.1.20:8443. Show me the pairing code and wait for my confirmation.

Complete the same code check above. The Skill handles both pairing and phone control; no separate MCP setup is needed.

## Documentation

[Usage & troubleshooting](docs/usage.md) · [Skill & MCP setup](docs/integrations.md) · [API reference](docs/api.md) · [Build from source](docs/validation.md)

[Contributing](CONTRIBUTING.md) · [Report an issue](https://github.com/nidhoggfgg/PhoneUse/issues) · [Security policy](SECURITY.md)

## License

[MIT](LICENSE)
