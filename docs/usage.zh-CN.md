# 使用与连接

[English](usage.md) | **简体中文**

## 先连接你的 AI

1. 从 [GitHub Releases](https://github.com/nidhoggfgg/PhoneUse/releases/latest) 下载 APK，安装到 Android 11+ 手机并打开 Phone Use。
2. 开启无障碍权限，按提示允许通知和局域网访问，保持手机亮屏、解锁。Agent 所在电脑需要 Python 3.10+，并能访问手机网络地址。
3. 在 **「连接 AI」** 卡片中选择可访问的手机地址，复制完整提示词发给 Agent。它会安装 Skill 并发起配对。逐位核对 Agent 和手机显示的八位码，在手机授权，再告诉 Agent「已核对一致」。

之后就可以让 Agent 操作手机，已完成的配对会在重启后保留。手动安装 Skill、MCP 接入和更新说明见 [AI 接入指南](integrations.zh-CN.md)。

## 权限与后台启动

部分 Android 系统需要先在应用详情中允许**受限设置**，才能为侧载 APK 开启无障碍。按提示授予通知权限；Android 17+ 还会请求局域网权限。

小米／Redmi／POCO 用户返回 App 页面时，会尝试检测「后台弹出界面」权限。首页仅在检测到未允许或上次启动未确认时显示「后台启动应用」提示；系统不支持检测时，仅在启动未确认后提醒。**「设置 → 后台启动应用」** 始终保留系统权限入口，可手动允许「后台弹出界面」或「后台打开新窗口」。打开设置页不代表已授权。

所有厂商在启动被拒绝或未确认成功时，都会另发一条可点击打开 App 的通知；重复失败合并显示，后续启动确认成功或在 App 内关闭提醒时清除。重试前先检查手机是否有应用跳转确认弹窗，或应用仍在加载。退出 App 后服务中断时，参阅[后台保活说明](#服务与配对恢复)。

## Web 控制台

App 的“打开 Web 控制台”始终打开 `http://127.0.0.1:8443/`，配置白名单后若未包含 `127.0.0.1`，该按钮禁用；加入此地址或清空白名单即可恢复。仅通过本机 `127.0.0.1` 访问时允许 HTTP 而无需开启不安全 HTTP 开关，仍受地址校验和配对授权限制。远程浏览器默认使用 `https://192.168.1.20:8443/`，需要独立建立浏览器证书信任；浏览器六位授权码不能替代 TLS 身份验证。控制台支持 API 搜索、参数表单、JSON 编辑及显式截图。

设置中的“允许不安全的 HTTP 连接”可开启同端口的其他 HTTP 访问，包括局域网地址、域名和 IPv6 回环。加入白名单不会开启 HTTP，开启 HTTP 也不会绕过白名单。该开关默认关闭，重启后保留，切换立即生效。关闭时已有的受此开关控制的 HTTP 请求也会断开。HTTP 不加密屏幕内容、操作和凭据，仍需配对和鉴权。HTTP 和 HTTPS 使用独立浏览器 Cookie，切换协议可能需要重新配对。

设置中的“访问地址白名单”支持每行一个 IP 地址或域名，不含协议、端口、路径或通配符。留空时仅允许通过手机的连接接收端 IP（含回环地址）访问，拒绝域名；填写后替换默认规则，仅允许列表中的目标地址，本机控制台也不例外。这里填写的是访问手机时使用的地址，不是连接者的来源 IP。保存后对后续请求生效，无需重启服务；清空列表恢复默认规则。域名精确匹配，不自动放行子域名。旧的公网地址设置会迁移为单条白名单，因此需要继续使用的手机 IP 也应加入列表。

配置白名单后，首页只展示列表中的地址；未配置时展示手机的非回环网络地址。默认展示 HTTPS，开启不安全 HTTP 后额外展示 HTTP。列出地址不代表网络已连通，DNS、路由和 8443 端口映射仍由部署环境负责。配对和授权要求不变。

浏览器用 HttpOnly Cookie 保存授权。关闭标签页、重启浏览器或 App 通常无需重新配对。可在手机撤销客户端，或在网页解除当前浏览器配对。清除 Cookie 或更换浏览器访问地址后需重新配对。网页不展示或导出长期 token。

查询或重试同一动作时保留原 `request_id`；仅为新动作生成新 ID。连接恢复不重放输入。

## Python 客户端

需要 Python 3.10+，无第三方依赖。在仓库根目录运行：

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --full-tree
```

`pair` 显示本地计算的八位码。逐位核对手机上的码，在手机批准，然后在 CLI 输入 `yes`。使用 MCP 时，告诉 AI 已核对一致，AI 才能携带 `confirm_pairing:true` 继续调用 `phoneuse_connect`。已完成配对自动复用；未完成请求两分钟过期，手机服务重启后清除。

默认配置为 `~/.phoneuse.json`。需要自定义位置时，在**子命令之前**添加 `--config /absolute/path/registry.json`。即使只有一台设备，也要传入返回的 `DEVICE_ID`。地址变化时用 `pair --url NEW_URL --device-id DEVICE_ID` 更新。桥接发送凭证前核验安装身份；旧配置没有可信设备 ID 时需要重新批准。

### JSON 参数与动作

为避免不同终端的引号规则影响参数，将 JSON 保存为 UTF-8 文件 `action.json`：

```json
{"request_id":"home-001"}
```

```sh
python tools/phoneuse_client.py call home @action.json --device-id DEVICE_ID
python tools/phoneuse_client.py call get_status @action.json --device-id DEVICE_ID
```

此 ID 仅为示例，每个新输入动作应使用新的唯一 ID。网络错误后查询原 ID，不要自动更换 ID 重放。节点操作需要观察返回的 `observation_id` 和 `element_id`；坐标动作也需要 `observation_id`，并依据截图的缩放与偏移换算屏幕坐标。

CLI 默认省略图片 base64，保留尺寸及坐标映射。`--screenshot-out PATH` 保存图片，`--include-image-data` 显式返回 base64。MCP 返回原生图片内容块。完整树使用 `output:"legacy",compact:false` 或 CLI `--full-tree`。

### 批量调用与可选占用

`batch @calls.json` 和 MCP `phoneuse_batch` 接受显式调用，每项提供 `device_id`、`tool` 和 `arguments`。同设备按顺序执行，不同设备独立并发。默认在某设备出现失败或未完成步骤后停止其后续调用。`--summary-only` 只返回汇总。批量调用不规划任务、不自动填入观察 ID，也不重试动作。

连续测试可通过 `acquire_device` 取得限时占用，在动作中携带 `lease_id`，结束时调用 `release_device`。设备未被占用时可直接操作。可随时在手机撤销客户端授权。执行状态和保留上限见 [API 参考](api.zh-CN.md)。

## 服务与配对恢复

打开 App 后服务自动监听 8443 端口。首页和通知栏显示服务状态，不再提供启动、暂停或停止开关；旧版保存的暂停或停止设置不再生效。已完成配对保留，但不会自动重放输入。

「设置 → 后台保活」默认开启：保留前台服务和常驻通知，系统回收后允许恢复，并在开机解锁、应用更新或系统重新连接无障碍服务后尝试启动控制服务。关闭后不再自动恢复，从最近任务中移除 App 时停止控制服务；正常切换到其他应用仍能继续当前会话，重新打开 App 可再次使用。切换保活不会清除配对、撤销无障碍授权或修改已授予的系统权限。

在保活设置中点击「电池优化设置」，确认允许豁免；返回后显示实际状态。点击「自启动设置」可尝试打开厂商页面，入口不可用时回退到应用详情。小米／Redmi／POCO 建议允许自启动、将省电策略设为「无限制」并锁定最近任务；华为／荣耀可手动管理应用启动，允许自启动、关联启动和后台活动；OPPO／realme／一加／vivo 可检查自启动与后台运行限制；三星可加入「从不休眠的应用」。厂商自启动授权没有通用检测接口，需自行确认；打开设置页不表示已授权。通知被禁用时也会提供设置入口。

保活不能绕过系统「强行停止」或保证所有厂商永不回收。强行停止后请重新打开 App；无障碍若已被关闭，需在系统设置重新开启，应用不能静默授权。实现参考 [Tasker 的后台运行建议](https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html)、[Android 电池优化豁免](https://developer.android.com/training/monitoring-device-state/doze-standby)和[前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。

删除客户端配置、清除 App 数据、卸载手机 App、清除浏览器 Cookie 或撤销授权后需重新配对。固定手机地址有助于稳定浏览器访问。MCP 与 Skill 默认共用配置，通过不同的 `--config` 路径可分别保存凭证。

## 连接排查

服务在 **8443 同端口接收 HTTPS 和 HTTP**。MCP／Python 客户端仍要求公钥绑定的 HTTPS，不自动降级。远程 HTTP 需要显式开启上述设置。白名单为空时 Host 必须匹配连接接收端 IP；配置后必须精确匹配列表中的地址，端口均须匹配服务端口。Origin 必须与请求协议及地址完全同源；转发头不能授予本机 `127.0.0.1` 的 HTTP 例外。不提供云端中继或自动端口映射。详见[传输安全](transport-security.zh-CN.md)。

开发时可选用 ADB 本机转发：

```sh
adb forward tcp:8443 tcp:8443
```

随后使用 `python tools/phoneuse_client.py pair --url https://127.0.0.1:8443` 连接。正常网络连接不需要 ADB。

同时更新手机和桥接。已有 HTTP 地址会在本地迁移为 HTTPS，但旧凭证会丢弃，必须重新核对配对。TLS 密钥变化不会自动接受；若独立核实确实进行了重装／重置，先在手机撤销旧客户端，再执行 `python tools/phoneuse_client.py forget-device --device-id DEVICE_ID`，然后重新配对。

无法输入时检查无障碍、锁屏和客户端授权。云端 AI 如果不能访问局域网或运行本地桥接，仅安装接入文件无法连接手机。

## 语言与设备名称

App 设置支持跟随系统、中文和 English。Android 11+ 会保存选择，Android 13+ 与系统应用语言同步；通知跟随选择。切换语言不重置服务或配对。系统设置中的无障碍服务说明由 Android 决定显示方式。

默认设备名优先使用系统名称，否则回退到品牌和型号；模拟器使用易读名称。厂商接口可能只返回型号编码。手动名称优先，不修改系统设备名或安装 ID。

Android 翻译位于 `app/src/main/res/values/strings.xml`、`values-zh/strings.xml` 和 `res/xml/locales_config.xml`，需保留格式化占位符和复数规则。Web 文案位于 `app/src/main/assets/web/i18n.js`，刷新后应用语言变化。API 名称、参数键、原始响应和服务端工具说明保持协议原样。
