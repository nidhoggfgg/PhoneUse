# 将 Phone Use 导入 AI

[English](integrations.md) | **简体中文**

推荐复制 App 提供的提示词发给 AI，从手机安装 Skill 并连接。原有 GitHub Skill 安装与 MCP 接入仍可使用。

## 从手机直接安装（推荐）

1. 安装并打开 App，开启无障碍，让 AI 的执行环境能够访问手机地址。
2. 在 **连接 AI** 卡片中，选择 AI 能访问的手机地址。
3. 点击下方提示词右侧的复制按钮，将整段内容发给 AI。提示词包含连接指令和形如 `https://手机地址:8443/connect#sha256=包校验值` 的链接。
4. AI 从同一端口读取接入说明、下载完整 Skill，按原始链接中的 SHA-256 校验，再安装到宿主的 Skill 目录。没有持久安装能力时，也可在工作目录解压、读取并直接执行客户端。
5. AI 显示八位码后，逐位与手机核对，在手机批准，并回复 AI 核对一致。之后使用保存的配对自动连接。

这条路径使用 **Python 3.10+ 标准库**。AI 必须具备文件读写、执行程序和访问手机网络的能力；仅提供 URL 无法赋予云端聊天这些能力。部分宿主需新会话才发现新安装的 Skill，当前会话可直接读取 SKILL.md 执行。

下载包与已安装的 App 版本一致，更新 App 后重新复制链接并让 AI 更新 Skill；旧链接的校验值变化时停止安装。保留本地定制备份和 `~/.phoneuse.json`。首次下载使用自签 TLS；仅公开、无凭证下载可跳过证书链校验，执行前仍需核对用户链接中的包哈希。配对和控制继续由客户端严格校验已绑定公钥。同源清单中的哈希单独只能检查完整性，不能认证下载来源。

公开接口为 `GET /connect`、`GET /skills/manifest.json`、`GET /skills/phone-use.zip` 与 `GET /skills/phone-use/SKILL.md`，均受原有地址白名单和 HTTP 设置限制。下载无需配对，控制接口仍需授权。

## GitHub 安装的前提

两者都需要本机 Python 3.10+，在线安装请使用 Node.js 22.20+（含 npm/npx，满足当前 Skills CLI 要求；MCP 启动器本身支持 Node.js 18+）；从 GitHub 获取包还需要 Git。手机启动 Phone Use 并开启无障碍，电脑能访问手机 IPv4，首次连接由用户在手机批准。截图和控制使用绑定公钥的 HTTPS。首次逐位核对八位码，在手机批准，并明确告诉 AI 核对一致后，它才能携带 `confirm_pairing:true` 继续。不提供云端中继。

Skill 命令从独立的 `nidhoggfgg/phone-use-skills` 仓库获取文件，MCP 命令仍使用 `nidhoggfgg/PhoneUse`。两者均读取默认分支，对应仓库必须对使用者可访问。当前方案无需注册 npm 包；`@nidhoggfgg/phone-use` 是包内名称，不代表已发布到 npm registry。

## 方式一：安装 Skill

```sh
npx skills add nidhoggfgg/phone-use-skills --skill phone-use
```

按提示选择 AI 客户端和项目/全局安装范围。安装器会一起安装 SKILL.md、Python 脚本与接口参考，只需下载独立的 Skills 仓库，无需获取 Android 主仓库、运行构建或额外安装 MCP。

让 AI 代为安装时，可明确客户端和范围。例如为 Codex 全局安装：

```sh
npx --yes skills add nidhoggfgg/phone-use-skills --skill phone-use --agent codex --global --yes
```

第一个 `--yes` 允许 npx 获取安装器，第二个跳过安装器的交互选择。仅当前项目使用时去掉 `--global`；其他宿主可在交互流程选择。重新加载 Skill 后说：

> 使用 $phone-use 连接 https://192.168.1.20:8443，显示核对码，等我在手机批准后查看当前界面。

可直接交给 AI 的安装指令：

> 请将 https://github.com/nidhoggfgg/phone-use-skills 的 phone-use Skill 安装到当前 AI 客户端，使用 npx skills add，安装范围为当前项目。检查 Python 3.10+ 是否可用，保留其他已安装的 Skill。安装后连接我的手机 https://手机IPv4:8443，首次配对由我在手机批准。

手机地址必须换成真实值；未提供时由 AI 询问。Skill 安装并不自动授权手机。

## 方式二：安装 MCP

MCP 名称同样为 `phone-use`，使用以下命令启动：

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

通常由 AI 客户端启动这个命令，不需要用户长期打开终端。该进程通过 stdin/stdout 与宿主通信，手动运行后没有提示、等待输入是正常现象。启动器自动寻找 Python 3.10+，可通过环境变量 `PHONEUSE_PYTHON` 指定解释器的绝对路径；值只放路径，不附带参数。

### Codex 一条命令注册

macOS / Linux：

```sh
codex mcp add phone-use -- npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

Windows（通过 cmd 启动 npx.cmd）：

```powershell
codex mcp add phone-use -- cmd /d /c npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

如果没有 Codex CLI，或要调整启动/工具超时，可以先生成配置片段再由 AI 合并到 Codex 设置：

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use print-config --host codex
```

输出 `[mcp_servers.phone-use]` 段，已设置 120 秒启动及工具超时。合并到 `~/.codex/config.toml` 时同名段只保留一份；保留其他配置。首次拉取 GitHub 包可能超过宿主默认启动超时，可先运行上述 print-config 命令完成缓存。

### 其他 MCP 客户端

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use print-config
```

生成当前系统适用的 `mcpServers` JSON。让 AI 将 `phone-use` 条目合并到宿主配置（如 Claude Desktop 的开发者配置）。路径由 npx 管理，配置中无需填入下载目录或 Python 路径；Windows 自动生成固定的 cmd 调用参数。其他宿主如使用不同配置格式，应按其文档转换，不能直接覆盖原配置。

可直接交给 AI 的安装指令：

> 请为当前 AI 客户端安装 Phone Use MCP，服务名 phone-use，启动命令为 npx -y --package=github:nidhoggfgg/PhoneUse phone-use。先检查 Node.js、Git 和 Python 3.10+；优先使用宿主的 MCP 添加命令，否则用该包的 print-config 生成片段并合并配置，保留其他服务。Windows 使用 cmd /d /c 启动 npx。完成后连接我的手机 https://手机IPv4:8443，配对由我在手机批准。

### 首次连接

重新加载宿主 MCP，让 AI 调用 `phoneuse_connect({"address":"https://手机IPv4:8443"})`。工具显示八位核对码，用户逐位核对并在手机批准，再明确告诉 AI 核对一致后，才能调用 `phoneuse_connect({"device_id":"返回的ID","confirm_pairing":true})` 完成领取。每次设备操作显式指定 device_id，之后自动沿用授权。

可选启动参数：`--url https://手机IPv4:8443`、`--name "My AI"`、`--config /absolute/path/registry.json`。自定义参数应由宿主按独立参数传递；Windows 使用 cmd 时需按该 shell 的规则正确引用，普通安装无需添加这些参数，地址通过 phoneuse_connect 提供。

MCP 支持 `2025-03-26`、`2025-06-18`、`2025-11-25`；未实现 2026 协议。Python 桥接返回原生截图图片块。手机离线时仍能初始化；长批量任务可拆分或提高宿主超时。

配对后通过 `notifications/tools/list_changed` 通知宿主刷新设备工具。如果宿主没有刷新，可使用 `phoneuse_call`，显式传入 `device_id`、`tool` 和 `arguments`。

### 本地源码或直连 HTTP

已有源码时，可将 `python /absolute/path/tools/phoneuse_client.py mcp-stdio --url https://PHONE_IPV4:8443 --name "My AI"` 配置为宿主的 stdio 命令。

自行管理 Bearer 凭证的程序也可连接 `https://PHONE_IPV4:8443/mcp`，使用不含 SSE 的 Streamable HTTP POST/JSON。原生配对及传输细节见仓库 API 参考。

## 更新与凭证

Skill 可重新运行安装命令更新。MCP 的 GitHub 默认分支安装可能被 npm 缓存；需要可复现版本时，将 `github:nidhoggfgg/PhoneUse` 改成带实际发布 tag 或 commit 的 `github:nidhoggfgg/PhoneUse#引用`，升级时更改引用后重启宿主。不要填写不存在的 tag。

默认 MCP 与 Skill 共用用户目录 `~/.phoneuse.json`；npx 缓存更新不会清除手机授权。如需分开授权，两边各用不同 `--config`。不要提交或分发该凭证文件。手机端可以随时撤销客户端。

云端 AI 如果不能执行本地程序或访问手机网络，安装成功后也无法控制手机。没有工具时先检查宿主是否支持 stdio MCP；无法操作时检查手机无障碍、锁屏和客户端授权。网络中断后应查询原 request_id，不自动重复动作。

## 维护与离线包

`skills/` 是指向 [phone-use-skills](https://github.com/nidhoggfgg/phone-use-skills) 的 Git 子模块；`skills/phone-use/` 是完整可安装目录，所有依赖文件均在其内部。克隆或拉取主仓库后先初始化子模块：

```sh
git submodule update --init --recursive
```

首次克隆可使用 `git clone --recurse-submodules https://github.com/nidhoggfgg/PhoneUse.git`。初始化后，编辑 Skill 前运行 `git -C skills switch main`。客户端和接口协议以 `tools/phoneuse_client.py`、`docs/api.md`、`docs/api.zh-CN.md` 为源；修改后运行：

```sh
python tools/sync_skill.py
python tools/sync_skill.py --check
python -m unittest discover -s tools -p "test_*.py"
npm test
npm pack --dry-run
```

先在 skills 仓库提交并推送同步后的文件，再将更新后的 `skills` 指针与源文件修改一并提交到主仓库：

```sh
git -C skills add phone-use
git -C skills commit -m "Update phone-use Skill"
git -C skills push origin main
git add skills
```

提交主仓库前还需暂存对应的源文件修改。CI 检出主仓库锁定的子模块提交，并检查同步副本是否过期。子模块使用 HTTPS 方便公开克隆；维护者可运行 `git -C skills remote set-url --push origin git@github.com:nidhoggfgg/phone-use-skills.git` 配置 SSH 推送。npm 使用显式 files 列表，不打包 Android 工程、凭证或截图，也没有安装时构建脚本。

仍支持 `python tools/export_integrations.py` 生成 `dist/integrations/phoneuse-mcp.zip`、`phone-use.zip`、中英文说明及校验和，供离线分发。离线 MCP 包使用 `python configure.py --host json` 或 `--host codex` 生成绝对路径配置，移动目录后重新生成。

安装格式参考：[Skills CLI](https://github.com/vercel-labs/skills#readme)、[npm exec / npx](https://docs.npmjs.com/cli/v11/commands/npm-exec/)、[Codex MCP](https://developers.openai.com/codex/mcp)。
