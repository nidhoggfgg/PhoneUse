# API 参考 v0.4

[English](api.md) | **简体中文**

## 接入与身份

监听 IPv4 `0.0.0.0:8443`，**仅支持 TLS 1.2/1.3 HTTPS**。每次安装在 Android Keystore 内生成不可导出的独立密钥与自签证书。原生客户端通过短码核对绑定 SHA-256 SPKI 公钥指纹，无需公共 CA 或复制长指纹。旧 HTTP 凭证失效，需重新配对。

### 提交承诺 → 揭示随机数 → 双端核对确认 → 批准 → 领取

AI 使用 `phoneuse_connect`，不直接处理凭证。首次返回桥接本地计算的八位码。用户逐位对照手机并在手机批准，再明确告诉 AI 核对一致，AI 才能携带 `device_id` 和 `confirm_pairing:true` 继续。服务器返回 `approved` 不能代替人的核对确认。CLI 的 `pair` 展示核对码后要求输入 `yes`。已配对设备重连无需再次确认。

原生协议详见[传输安全](transport-security.zh-CN.md)。包括首次连接在内，所有请求使用 HTTPS：

1. 无凭证 `GET /identity` 从 TLS 连接取得候选公钥。后续每个连接均在发送 HTTP 数据前验证该指纹。
2. `POST /pair/request` 携带 `client_name`、`protocol:"phoneuse-sas-v1"`、`commitment`。HTTP 202 返回 `status:"committed"`、`protocol`、`pairing_id`、`pairing_secret`、`server_nonce`、`expires_in:120`、`poll_interval:2`，不返回核对码。
3. 桥接固定以上内容，向 `POST /pair/reveal` 提交 `pairing_id`、`pairing_secret`、`client_nonce`。验证成功返回 `status:"pending"`，手机才显示批准界面。双方独立计算八位码。
4. 两端完成核对确认后，用 `pairing_id` 和 `pairing_secret` 轮询 `POST /pair/status`。状态包括 `committed`、`pending`、`denied`、`approved`。批准后附带 `client_id`、`token`、`token_type:"Bearer"`、`api_path:"/api"`、`mcp_path:"/mcp"`。
5. 在已验证公钥的 HTTPS 连接中发送 `Authorization: Bearer TOKEN`。核对码或秘密不得放入 URL。

请求创建两分钟后过期，批准不延长时限；服务重启清除请求。期限内重复领取返回同一凭证，手机仅持久保存 token 摘要。最多 16 个请求、16 个客户端，新请求全局间隔至少五秒。承诺验证失败永久拒绝该请求；未完成揭示的请求不可批准。撤销授权清除可领取凭证，并使排队输入失效。

### Web 入口与传输

`GET /` 是不含凭证和设备数据的公开页面，允许从 App/其他页面跳转（包括 `Sec-Fetch-Site: cross-site`），仍校验 Host；脚本、配对和 API 请求继续使用同源策略。页面禁止被嵌入 frame。

`GET /` 提供内置 Web 控制台，`GET /app.js` 提供脚本，无须认证。`GET /api/tools` 需已配对客户端认证，返回 `{"tools":[...]}`，与 MCP `tools/list` 共用完整参数定义。

API/MCP 端点接受原生客户端的 `Authorization: Bearer TOKEN` 或浏览器自动携带的配对 Cookie。两者同时提供时以 Authorization 为准。所有 POST 请求使用 `Content-Type: application/json`；支持 Content-Length 和 chunked，正文最多 64 KiB，已鉴权的语音请求上限为 4 MiB。仅允许本服务同源浏览器请求，不提供跨域 CORS；Host 必须为连接所用手机 IPv4 和端口（例如 `192.168.1.20:8443`），Origin 若存在必须为相应 `https://IP:PORT`。域名或反向代理接入暂不支持。无 Origin 的原生客户端仍可使用。

浏览器使用 `POST /browser/pair/request` 和 `POST /browser/pair/status`，浏览器依赖浏览器自身的证书信任；请求正文为 `{"client_name":"browser"}`，采用原有六位授权核对码，不执行原生八位 SAS 流程。浏览器证书警告不能由网页或桥接自动消除。批准响应不含 `token/token_type`，改为 `Set-Cookie: phoneuse_client_PORT=...; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=34560000`。Cookie 最长保存 400 天，每次恢复会话时续期；浏览器自身的清理策略仍可能提前移除。Cookie 设置 Secure，仅在 HTTPS 中发送。页面 JavaScript 不读取长期凭证，关闭标签页或重启后由浏览器自动携带。

- `GET /browser/session`：HTTP 200 返回 `{"paired":true,"client_id":"..."}` 或 `{"paired":false,"client_id":null}`，验证已有授权并续期/清除 Cookie，不签发新身份。
- `POST /browser/session`：携带已有 Bearer 或 Cookie，正文 `{}`，换成同一身份的持久 Cookie。用于旧版打开标签页的自动迁移，成功后删除 sessionStorage 中的旧 token。
- `POST /browser/forget`：需认证，正文 `{}`；撤销该客户端、清除 Cookie 与配对暂存响应，使排队动作失效。关闭页面本身不调用此接口。

页面启动、重新获得焦点和网络恢复时检查连接；启动连接失败每五秒重试读取会话，保留配对。任何输入动作都不自动重试。网页不再导出长期凭证，旧 HTTP 导出凭证不可复用，必须重新配对。

### AI 的持久连接

`python tools/phoneuse_client.py mcp-stdio --url https://PHONE:8443` 在未配对或手机离线时也可处理 initialize。一个桥接管理多台设备；`phoneuse_connect` 按地址添加或按 `device_id` 重连，`phoneuse_list_devices` 返回安装级 ID、名称、型号、Android 版本、地址、在线状态和模拟器标记。后续每次设备调用必须显式指定 `device_id`，不维护共享“当前设备”。`phoneuse_call` 是未刷新列表宿主的备用调用入口；配对后也直接提供手机工具。

`GET /identity` 无需凭证，返回 `device_id/name/model/android_version/is_emulator/service_instance_id`。App 安装级 UUID 与地址分离，名称可在手机本地修改；身份与配对偏好设置排除备份/设备迁移。桥接在发送认证请求前先验证公开设备 ID，地址对应其他安装时不发送旧 token。换地址重连也必须匹配已保存身份。旧配置缺少可信设备 ID 时需要重新在手机批准，不能只凭 IP 迁移凭证。每个原生连接还必须在发送 HTTP 数据前验证保存的 TLS 公钥。旧 HTTP 凭证必须重新短码配对；密钥变化时拒绝连接，不会自动替换指纹。

配对请求/领取结果含设备身份；未完成请求也持久保存，批准后原子保存凭证。MCP 结果不返回 token 或领取秘密。不同设备独立连接、锁与执行队列，可并行；同设备调用串行。单台离线不阻塞其他设备。观察 ID 同时绑定客户端、设备服务运行实例及无障碍服务实例，跨设备、跨客户端及重启后的引用拒绝执行。

配置默认位于用户目录 `.phoneuse.json`，由桥接读写，不加入 MCP 返回；未完成配对也保存到该文件以便桥接重启后继续。401 清除已撤销凭证并提示显式调用连接工具；网络错误不删除凭证、不发起配对、不重放动作。手机仍未恢复时初始化与连接工具可用。此机制不隔离与桥接使用同一系统账户的文件访问权限。

每个连接只处理一个 HTTP 请求并关闭；HTTP 连接关闭不影响配对授权。连接池和等待队列有上限。

## 普通 API

`POST /api` 正文：

```json
{"tool":"observe","arguments":{"mode":"both","max_dimension":1280}}
```

响应直接返回工具结果。设备错误使用 HTTP 200 和 `state/error`；鉴权、HTTP 格式错误使用对应 HTTP 错误码。所有动作参数名称和类型由 API/MCP 共用的 ToolCatalog 校验；未知字段拒绝。

| 工具 | 参数 |
| --- | --- |
| get_capabilities | 无 |
| get_status | 可选 request_id，只能查询本客户端结果；返回 paused、stopped、executing_request_id |
| cancel | 可选 request_id，不传则取消本客户端所有未完成输入 |
| observe | mode：tree / screenshot / both（默认）；max_dimension：320–2048，默认 1280；compact：默认 true；stable_wait_ms：0–2000，默认 0（不等待）；max_attempts：1–3，默认 3；timeout_ms、wait_until、output、diagnostics、include_details、region、subtree 见下文 |
| acquire_device | 可选 ttl_ms：1000–300000，默认 60000；续期必须携带当前 lease_id |
| release_device | lease_id：释放本客户端的测试占用 |
| list_apps | 无；仅列出系统允许发现且带启动入口的应用 |
| wait_for_change | ui_version；timeout_ms：1–10000，默认 5000 |
| tap | element_id，或 x/y |
| long_press | element_id，或 x/y；坐标动作 duration_ms：500–5000，默认 600 |
| send_voice | 观察到的语音按钮（element_id 或物理坐标 x/y）、客户端已有的 audio_file，可选 sent_marker |
| swipe | points：2–64 个 {x,y}；duration_ms：1–5000，默认 600 |
| scroll | element_id 或 region:{left,top,right,bottom}；direction |
| input_text | observation_id、element_id、text、mode:replace/insert |
| back / home / recents | 无额外参数 |
| launch_app | package_name |

### 发送语音消息

发送语音是可选功能，在 Phone Use 支持的 Android 版本上均按设备实际能力检查。Phone Use 需安装在主用户下。实验性的辅助进程以 ADB shell 身份运行，检查音频路由权限以及系统能否为指定 App 的 UID 建立录音注入规则。安装 APK、启动 Phone Use 后，在单独的 PowerShell 或 bash 终端运行以下命令，测试期间保持终端开启。

```sh
adb shell 'CLASSPATH=$(pm path ai.nidhoggfgg.phoneuse | head -n 1 | cut -c9-) app_process / ai.nidhoggfgg.phoneuse.voice.VoiceShell'
```

看到 `Phone Use voice helper attached` 后，用 `get_capabilities` 检查 `send_voice.routing_api_available`。值为 false 时可查看 `unavailable_reason`；值为 true 表示通过初步检查，每次发送仍会验证所选 App 的录音通道。观察聊天 App 的“按住说话”按钮，再通过 CLI 或 stdio MCP 调用 `send_voice`。将下面的 ID 替换为本次观察值和新的请求 ID。

```json
{"request_id":"NEW_UUID","observation_id":"OBSERVATION_ID","element_id":"ELEMENT_ID","audio_file":"C:/audio/message.wav"}
```

客户端读取单声道 PCM16 WAV 文件，支持 8000–48000 Hz、最长 30 秒。直接调用手机端 HTTPS API/MCP 时，传 `sample_rate` 和 `pcm_base64`（小端有符号 PCM16 采样，不含 WAV 文件头）。已配对客户端的语音请求支持最多 4 MiB JSON。

Phone Use 在按下前为目标 App 的 UID 准备录音通道。按下后最多等 2.5 秒，确认目标 App 新开了录音、正在使用本次注入通道且未被静音，才送入音频。播放位置到达最后一帧后松手。录音中断、目标窗口改变或控制被撤销时，辅助进程会尝试取消触摸。只要可能触碰过按钮，就应先观察会话再决定下一步。

`execution.confirmation` 为 `released` 表示已松手。可重新观察会话确认消息，也可传 `sent_marker` 指定该 App 能唯一标识本条消息发送成功的可见文字或无障碍描述。只有该标记在松手后 1.5 秒内新出现在同一窗口，才返回 `sent_confirmed`。没有可靠标记时省略它。请求仍在执行时，用原 `request_id` 查询 `get_status`。Phone Use 进程退出后需重启辅助进程。

### 动作公共字段

所有 `tap` 至 `launch_app` 输入动作必填 `request_id`。`request_id` 最多 128 字符。可选：

- `observation_id`：关联设备、显示几何和目标引用；节点及坐标动作（tap/long_press/send_voice/swipe/scroll/input_text）必填，导航/启动应用可选。
- `expected_window_id`：预期的活动窗口。
- `observe_after`：动作后附带新观察，默认 false。
- `observation_options`：复用 `observe` 的观察参数；仅在 `observe_after:true` 或 `observe_on_rejection:true` 时允许。不是动作目标参数，不能包含 request_id、device_id 等字段。
- `observe_on_rejection`：默认 false；在目标校验类错误已经终止且确认 `action_executed:false` 时，按需附带 `recovery_observation`。暂停、撤销、停止等读取限制仍生效，附带观察错误不会覆盖原动作错误。
- `lease_id`：可选的开发者测试占用；设备有占用时必须匹配占用客户端和 ID。没有占用时可直接执行动作。

所有工具可携带 `device_id`、`service_instance_id`，由 HTTP 入口在分发前核验。桥接自动添加这两个保护字段，直连 API 可自行指定。所有工具结果及嵌套动作/观察结果包含 `device_id` 和 `service_instance_id`。地址不匹配返回 `DEVICE_MISMATCH`，服务实例不匹配返回 `STALE_OBSERVATION`，均明确 `action_executed:false`。

节点与坐标不可混用。节点操作只使用节点明确支持的操作，不自动转成坐标点击。节点滚动支持 forward/backward；区域滚动支持 forward/down、backward/up、left/right，方向指内容浏览方向。文本最多 10000 字符，允许空串清空。

`input_text` 直接重新验证所选编辑节点，不自动聚焦、点击、读回或等待页面。返回 `text_verified:false`，系统接受不表示文本已验证。AI 可独立观察检查文本，或显式请求附带观察。`replace` 使用 ACTION_SET_TEXT 替换全部内容。`insert` 读取现有文本和 selection，将选区替换为给定文本，再通过 ACTION_SET_TEXT 写入组合结果，并尝试恢复插入位置的光标。编辑节点标记 isShowingHintText 时，提示文字保留为 hint，输入内容视为空串；可读空串且系统选区为 (-1,-1) 时使用唯一插入位置 (0,0)。非空或不可读文本的未知选区、密码字段返回 ACTION_UNSUPPORTED。光标恢复是可选执行步骤：未声明 ACTION_SET_SELECTION 的编辑框仍可执行文本插入，但不尝试恢复光标。插入后 `cursor_restored` 表示 Android 是否接受光标恢复；null 表示无须恢复、节点未声明选区设置能力或取消后未尝试。它不是完整输入法，富文本、特殊编辑器和应用行为需要实机验证。

## 观察与坐标

观察包含 device_id、service_instance_id、observation_id、ui_version、captured_at_ms（Unix 毫秒）、window_id、package_name、display、tree、screenshot，以及 collection_attempts。

- 坐标是默认显示器当前旋转方向下的**物理像素**，左上角为 (0,0)，包含系统栏和键盘区域。不是 dp，也不是裁剪后的应用坐标。
- JPEG 默认长边不超过 1280，质量 80。返回宽高、scale_x/scale_y、offset_x/offset_y。
- 映射：`screen_x = image_x * scale_x + offset_x`，y 同理。动作须传屏幕坐标。
- 旋转值为 0/90/180/270。旋转或几何变化会使关联快照失效。
- tree.elements 默认精简：过滤不可见、空边界、屏幕外及无有效内容/动作的节点，保留必要父子结构，以 parent_id 指向最近保留父节点。element_id 保留原始树路径，不能用数组序号代替。默认省略 enabled:true、visible:true 和为 false 的状态字段，可读编辑字段提供 selection。`output:"legacy"` 下 `compact:false` 返回完整的有界树和状态字段；decision 模式仍按其投影规则过滤节点和省略默认状态。CLI 与 MCP 使用同一服务端输出。legacy 模式的未命名 Android 动作以十进制 ID 表示。
- Phone Use 自身的配对/授权界面不返回树内容，也不接受远程点击或文本操作（LOCAL_CONTROL_UI）；可通过 home/back/recents/launch_app 离开。仅支持默认显示器，其他显示器返回 DISPLAY_UNSUPPORTED。
- 元素 ID 是快照内部路径，每个客户端最多保留八份快照，每份最长 30 秒；已缓存结构指纹总估算内存预算 4 MiB，不在快照缓存中保留截图。客户端只能淘汰自己的快照；预算不能容纳新快照时返回 BUSY、reason:snapshot_budget_exceeded，不挤掉其他客户端的有效引用。不能将元素 ID 当作永久 ID。
- 最多采集 1500 个节点，深度最多 40，单个文本/描述截断至 1024 字符。password 文本不返回。节点指纹使用完整字段的摘要，不依赖截断文本。
- 截图失败时 `screenshot.available=false`，附带原因和 Android 错误码；与 `tree.available=false` 分别报告。未请求的部分为 null。
- 默认立即采集当前可用状态；只有显式 `stable_wait_ms>0` 才等待最多该时长的短暂安静（120 ms），不代表业务就绪。窗口/应用切换或显示几何改变引发有限重采；普通内容事件、持续动画和窗口清单暂不完整不使观察整体失败。无条件观察最多尝试 max_attempts 次；条件观察可在总预算内进行多轮。只重采观察，绝不重放动作。
- 节点动作沿原路径找到候选，核验节点身份、目标字段、可见/可用状态及请求动作，不采集整页树、不比较祖先布局或容器子树。路径相同但节点已替换仍拒绝；目标文本、边界或可用状态改变仍需要重新选择。replace 忽略单纯选区变化；insert 匹配原文本和选区。
- 坐标动作关联默认显示屏，保留引用归属、有效期、服务实例、显示尺寸/旋转和坐标范围检查。普通内容刷新、活动应用变化、输入法出现/消失和跨窗口路径不整体作废坐标；只有显式 expected_window_id 才要求指定活动窗口。Phone Use 自身窗口和通知中的本地控制按钮始终保护。
- 不以 sibling 边界、绘制顺序、高层窗口重叠推断操作失败；AI 结合截图选择节点或坐标机制。系统拒绝如实返回，不自动重试、降级或选择其他目标。
- 错误保持原 code，并补充 `error.reason`、可取得的 `snapshot_age_ms/snapshot_ui_version/current_ui_version/events`。区分过期、淘汰、客户端/实例变化、显示变化和目标缺失/替换/字段变化。动作前拒绝返回 `action_executed:false`；无法确认是否输入时该字段为 null。

界面事件异步到达，以上检查不能消除所有竞争条件。屏幕内容可在最终检查与系统动作之间变化。

### 条件观察与总预算

独立 `observe` 和动作 `observation_options` 接受同一组参数。`timeout_ms` 为 1–60000 毫秒，配置 `wait_until` 时默认 10000，未配置时默认 20000。预算覆盖安静等待、树采集、截图、一致性重采和条件检查；`stable_wait_ms`、`max_attempts` 只控制每轮采集，不代替总预算。独立观察可能超过动作的 15 秒同步窗口，CLI/Web 对独立观察使用 90 秒 HTTP 读取上限，为排队和返回留出余量；连接中断也不会重试输入。

`wait_until` 至少包含 `package_name` 或 `element`；所有给出的字段按 AND 组合。包名只确认当前应用，不能证明业务页面已完成加载。元素支持 `view_id`、`text`、`description` 精确匹配，至少提供其中一个；可同时要求 `visible`、`enabled`、`editable` 或 `action`。`action` 只允许 `tap`、`long_press`、`set_text`、`scroll_forward`、`scroll_backward`。条件不接受 element_id。

`element.match` 默认 `unique`，零匹配为未满足，多个匹配为歧义；仅等待某类节点出现时可指定 `exists`。条件基于完整的有界内部采集，在输出投影前判断；采集截断或子节点读取缺失时无法证明唯一性则报告 unknown，不能从局部树推断全局唯一。collection_complete 与 collection_truncated 分别报告完整性和采集边界，未截断也可能读取不完整。

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

观察结果分别表达：

- `condition.status`：satisfied / unsatisfied / ambiguous / unknown；`timed_out` 独立表示预算到期。还可包含 package_matched、match_count、match、element_ids、collection_truncated、collection_complete。未配置条件时不返回 condition。
- `observation_status`：complete / condition_timeout / failed。complete 表示本轮采集和条件流程完成，仍须检查请求的 `tree.available`、`screenshot.available`；截图失败不会被误写成原动作失败。
- `inconsistency_detected`、`collection_attempts`：报告本次流程检测到的不一致和采集次数；重采成功不抹去已检测到的不一致。
- `failure_stage`：失败或超时发生阶段。采集失败时当前条件为 unknown；可附 `last_observation`，保留其原 captured_at_ms 与该次 condition。历史条件不代表当前条件。

条件超时保留最后一次有效观察及 unsatisfied / ambiguous / unknown 判定，不能当作就绪；后续采集失败也不能直接当成条件未满足。动作执行事实、观察可用性和条件达成必须分别处理。稳定骨架屏、空树或重复目标不会自动满足唯一目标条件。

condition.element_ids 用于说明内部匹配结果；节点动作仍只能引用该观察 tree.elements 实际导出的节点。仅截图模式或范围投影可能不导出匹配节点，需要操作时请求包含该节点的树观察。

树和截图分别返回 started_at_ms / finished_at_ms 及 available；请求其中一项时不采集另一项作为替代。显式截图条件包含 element 或 subtree 时，内部按请求采集节点用于匹配，但不导出树。`elapsed_ms`、`wait_ms`、`collection_ms` 分别报告总耗时、明确等待耗时和采集处理耗时。

`diagnostics:true` 返回采集 started_at_ms、finished_at_ms，以及有界 captures 中的前后窗口、包名、ui_version、时点和 inconsistency_reason；`capture_diagnostics_truncated` 表示诊断列表截断。`consistency_scope` 说明检查范围。这些检查不承诺截图与树具有同帧原子性。

### 决策输出与范围

`output` 默认 `decision`；需要旧格式时显式传 `output:"legacy"`。`output:"decision"` 只改变对外树投影，保留节点路径 element_id、必要的 parent_id、内容、边界、真实支持的动作及影响操作的状态。仅有 hint、error、state_description 的节点也可保留。默认省略 class、view_id 和未命名动作；`include_details:true` 展开 class、view_id、raw_actions。未知 Android 动作不会被当作可执行动作。

决策输出会将可明确归属的静态叶子文字合并到最近的真实点击/长按目标，生成 `label` 和 `label_sources`。`label` 是显示摘要，不替代原始 `text/description`，也不用于 `wait_until` 精确匹配；`label_sources` 只是文字来源路径，不能直接用作动作引用。动作仍使用 `elements` 中目标的原始 `element_id`、边界和真实动作。独立按钮、编辑框、状态节点以及带 collection/collection_item 的列表分组会保留；合并不会跨越有语义的分组、滚动区域或范围外目标，也不会合并边界不被目标包含的文字。标签按原树顺序、description 在 text 前精确去重，最多合并 8 个来源节点且摘要不超过 1024 字符，超出预算的节点保留。`tree.merged_nodes` 表示合并掉的节点数。`include_details:true` 展开被合并的文字节点并保留摘要；legacy 输出不执行语义合并。需要引用文字来源节点时，请先获取展开后的新观察。没有无障碍分组证据的视觉卡片不会凭空推断分组。

精简布尔状态按 `tree.state_defaults` 解读：enabled、visible 默认为 true；editable、password、focused、checkable、checked、selected、content_invalid 默认为 false。节点输出其偏离默认值的状态。省略字段不能被误读为缺少编辑能力或与默认值相反的状态。

`region:{left,top,right,bottom}` 使用显示器物理像素，保留与区域相交的节点；`subtree:{observation_id,element_id}` 使用同客户端的原快照定位子树，读取前必须重新验证目标，不能直接套用旧路径。两者可共同限制输出，不缩小内部采集范围，也不裁剪截图。导出的节点仍受原观察、设备/显示和目标校验约束。

`tree.collection_truncated`（兼容字段 truncated）表示采集达到边界；`collection_complete` 还检查子节点读取是否缺失；`scope_clipped` 表示请求了范围投影；`collected_nodes`、`output_nodes` 分别为内部采集和导出节点数，`output_bytes` 仅为 elements JSON 的 UTF-8 字节数。客户端展示截断应另行记录，不能混同这两种服务端限制。输出减小不代表截图成本消失。

### 显式聚焦后输入

若截图显示输入区域、但当前树没有可编辑节点，应使用当前截图映射坐标显式聚焦，再用新观察确认编辑能力。示例坐标和节点 ID 必须替换成现场观察结果：

1. `observe {"mode":"both","output":"legacy","compact":false,"include_details":true}`，保存聚焦前的完整有界树与截图。
2. `tap {"request_id":"focus-001","observation_id":"BEFORE_ID","x":320,"y":240,"observe_after":true,"observation_options":{"mode":"both","output":"legacy","compact":false,"wait_until":{"element":{"view_id":"com.example.shop:id/search","editable":true,"enabled":true,"action":"set_text"}},"timeout_ms":5000}}`。
3. 仅当新观察的 condition 为 satisfied，且树确认唯一节点可编辑并支持 set_text 时，调用 `input_text {"request_id":"type-001","observation_id":"AFTER_ID","element_id":"AFTER_ELEMENT_ID","text":"搜索词","mode":"replace"}`。

聚焦前后分别保存树，不能仅凭外观推断编辑能力。条件超时或编辑节点仍不明确时，独立 observe 后重新决策；不重放聚焦动作，也不自动改写为坐标输入。

## 授权、执行与去重

配对授权后可直接调用输入动作，无需申请或释放控制权。所有客户端的动作和即时读取共用单线程设备队列（最多排队 32 个），变化等待不占设备队列。只读请求也必须鉴权，暂停时观察/应用列表/等待被拒绝，状态与能力仍可查询。

连续开发测试可选用 `acquire_device` 取得最多五分钟的占用；有未完成动作时不能新建占用，避免接管中途动作。有效期内其他客户端或缺少正确 lease_id 的输入返回 DEVICE_RESERVED；过期、释放后的旧 lease_id 返回 LEASE_EXPIRED。入队及系统输入前都再次验证，排队期间过期的动作不会执行。占用不限制只读观察，不改变配对授权。本地暂停、停止和撤销占用客户端立即清除占用；已注入动作可能完成。get_status.reservation 返回占用状态和剩余时间，不泄漏占用 ID。

授权凭证持续有效，直到手机端撤销。暂停阻止新操作并取消排队输入，恢复后可直接提交新动作。进程和服务重启不重放请求；重新启动服务后仍须鉴权。

普通动作默认只执行一次并返回执行事实，不固定延时、不采集动作后树或截图；观察缺省是正常成功结果。`observe_after:true` 才附带观察，额外等待仍需在 observation_options 中明确请求。AI 决定调用顺序，有效引用可以连续使用，不要求每次动作后重新观察。

动作最长同步等待 15 秒；尚未完成时返回 accepted（排队）或 executing（执行中）。继续查询 get_status，不把执行中当作成功。动作已执行但仍在等待观察时，顶层及 `get_status.request` 保持 `state:"executing"`、`action_executed:true`、`observation_status:"observing"`，`observation_purpose` 区分 after_action / after_rejection；未请求附带观察时省略 observation_status 和 observation_purpose。`accepted` 与 `action_executed:false` 同时出现只表示尚未执行，不能证明请求已经终止。终态：

```json
{
  "state":"executed",
  "action_executed":true,
  "request_id":"tap-001",
  "execution":{"confirmation":"android_accepted","business_success":null}
}
```

- executed：Android 接受节点/导航/Intent 操作，或报告手势完成。不会声称业务成功。
- failed：校验或系统明确拒绝，含 error.code/message。
- cancelled：尚未执行的输入被取消或控制已失效。
- unknown：手势回调超时、部分手势取消或无法判定执行结果的异常。先重新观察，不自动重试。

执行成功但后续观察失败或条件超时仍返回 executed；observation 单独包含状态和错误。条件未满足的结果必须阻断依赖该条件的后续调用。已经注入的动作不会因通知暂停而被伪装成撤销。cancel 返回 in_flight_may_complete；手势无法保证立即终止。

`recovery` 是有限类别的建议对象，包含 category、advisory:true、replay_action:false、suggested_tool 和 message，不保证下一次操作成功。排队/执行中使用 wait_original_request；unknown 使用 reconcile_result；终态未执行的目标错误、执行后观察失败或条件超时使用 refresh_observation。先区分请求是否终止，再决定观察或查询原请求；不根据 action_executed:false 单独创建替代动作。恢复观察位于 recovery_observation，失败可再嵌套 last_observation。CLI/Web 递归识别这些层级，截图附件标明来源，历史截图不改变当前条件或错误。

窗口信息只作按需诊断（`diagnostics:true` 下的 windows），不要求活动应用覆盖全屏，也不需要客户端先选择输入法窗口。版本和事件历史用于解释变化，不是整页必须不变的前置条件。

去重作用域为**当前服务进程的 client_id + request_id**，相同 ID 的参数和工具必须完全一致（对象键顺序忽略）。结果保留十分钟，最多 256 个 ID，并有约 800 万字符的结果缓存预算；预算满时拒绝新动作，不提前丢弃未到期的 ID。快照/截图/动作参数只在内存保存，服务停止或进程死亡后不恢复。应用不写操作日志或截图文件。

若已执行动作的附带观察使结果超过剩余保留预算，该观察会替换为 `observation_status:"failed"`、`error.code:"CACHE_BUDGET_EXCEEDED"`、`omitted_for_retention:true`，保留原动作 state、action_executed 及原始错误。不能因此再次发送原动作。

若其余诊断仍超过预算，结果标记 `diagnostics_omitted_for_retention:true`，省略详细窗口/事件诊断和执行附加信息，保留原错误 code、reason、年龄/版本及执行确认字段；execution 标记 `details_omitted_for_retention:true`。过长的平台错误消息会截短并标记 `message_truncated_for_retention:true`。这些裁剪不改变动作状态或执行事实，也不会单因诊断裁剪而将观察标为失败。

重发相同 ID 返回已有结果，不再次输入，也不重新开展附带观察；不同参数返回 REQUEST_ID_CONFLICT。缓存观察的采集时间和有效期不会刷新，需要新观察时独立调用 observe。十分钟之后或进程重启后不能依据去重保证安全重放。客户端必须自行保留不确定请求，并重新观察。

通知提供暂停/恢复和停止服务。暂停使排队输入失效，恢复不会重放已取消动作。撤销客户端凭证会拒绝其后续读写请求并取消其排队输入，不影响其他客户端。停止关闭 HTTP 入口，并使所有尚未执行输入失效。

## 主要错误码

INVALID_ARGUMENT、UNKNOWN_TOOL、UNAUTHORIZED、PAUSED、STOPPED、CANCELLED、BUSY、DEVICE_MISMATCH、DEVICE_RESERVED、LEASE_EXPIRED、REQUEST_ID_CONFLICT、REQUEST_NOT_FOUND、ACCESSIBILITY_UNAVAILABLE、SCREEN_OFF、DEVICE_LOCKED、NO_ACTIVE_WINDOW、OBSERVATION_INCONSISTENT、OBSERVATION_TIMEOUT、STALE_OBSERVATION、STALE_ELEMENT、WINDOW_MISMATCH、ELEMENT_UNAVAILABLE、ACTION_UNSUPPORTED、ACTION_REJECTED、APP_UNAVAILABLE、RESULT_UNKNOWN。OBSERVATION_INCONSISTENT（采集中变化）、STALE_OBSERVATION（动作上下文失效）、STALE_ELEMENT（目标变化）分别保留，便于批量汇总。

## MCP

`POST /mcp` 使用 JSON-RPC 2.0。支持 initialize、ping、tools/list、tools/call、无响应的通知；不支持批处理。每个工具使用同一套执行代码。notifications/initialized 返回 HTTP 202；通知永远不会触发工具动作。

这是无服务端 MCP 会话的 Streamable HTTP POST/JSON 模式，不返回 Mcp-Session-Id。GET/DELETE 返回 405，无 SSE 推送。MCP 版本为 2025-03-26、2025-06-18 或 2025-11-25；缺省头兼容早期版本。2026-07-28 的无 initialize 协议暂不支持。

tools/call 结果为 text 内容块；观察中的图片另作为 image 内容块，包括 request、observation、recovery_observation、last_observation 内的截图。文本保留截图映射、来源层级与原采集时间，去除重复的 data_base64。工具错误、观察失败、条件未满足及请求的树/截图不可用通过 isError 表示，原动作执行事实仍保留；accepted/executing 中的 observing 本身不表示错误，仍须查询原请求。MCP JSON-RPC 的 id 不是动作去重 ID；仍须传 arguments.request_id。用 cancel 工具取消动作；通用 JSON-RPC 取消通知不直接撤销设备输入。

### 桥接批量测试

`phoneuse_batch` 接收 1–100 个显式调用，示例：

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

同设备按输入顺序执行，不同设备并行；结果恢复为输入顺序。可在每项 arguments 中传 lease_id，也可使用该项顶层 lease_id。默认某设备步骤失败、结果未知、仍执行中、条件未满足/歧义/未知、请求的树或截图不可用、缺少请求的附带观察时，跳过该设备后续步骤，其他设备继续。get_status 按嵌套 request 判定；last_observation 的历史条件不能使失败的新观察变成就绪。需要在失败后继续的独立测试可显式设置 continue_on_error；不会重试已发送动作。

summary 包含 total、succeeded、failed、pending、unknown、skipped，以及 states、error_codes、observation_error_codes；新增 observation_incomplete（观察未完成的调用数）、observation_statuses、condition_statuses。附带观察失败的已执行动作仍保留 succeeded 的兼容计数，并另计 observation_incomplete，不能把 succeeded 当业务成功率。condition_statuses 统计当前观察与恢复观察，不以历史 last_observation 改写当前条件。观察错误覆盖嵌套恢复观察。MCP 批量结果在 observation_incomplete 非零时也标记 isError。

summary_only 省略逐项结果；批量结果不携带任何层级的截图 base64。CLI 的 `batch @calls.json` 读取上述 calls 数组，`--summary-only` 与 `--continue-on-error` 对应同名选项。`call ... --screenshot-out PATH` 可从嵌套请求、恢复观察或 last_observation 保存最新有数据的截图，并返回 screenshot_output_source 与 saved_path；图片保存失败不会抹去已执行动作结果。

## 实现参考

- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 17 局域网权限](https://developer.android.com/about/versions/17/behavior-changes-17)
- [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
