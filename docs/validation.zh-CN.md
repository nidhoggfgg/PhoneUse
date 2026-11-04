# 开发与验证

[English](validation.md) | **简体中文**

## 从源码构建

使用 JDK 25、Android SDK 37 和仓库内的 Gradle wrapper。SDK 路径在本机配置，不提交机器路径。

```sh
git clone --recurse-submodules https://github.com/nidhoggfgg/PhoneUse.git
cd PhoneUse
./gradlew :app:assembleDebug
```

Windows 使用 `.\gradlew.bat :app:assembleDebug`。将 `app/build/outputs/apk/debug/app-debug.apk` 安装到 Android 11+ 手机即可。构建会把客户端、API 参考和 Skill 指引打包进 APK。已有仓库构建前先运行 `git submodule update --init --recursive`，初始化或更新 Skill。

## 自动检查

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

Windows 将 `./gradlew` 替换为 `.\gradlew.bat`。Python 检查需要 Python 3.10+，启动器测试需要 Node.js。Web 控制台测试另需 Playwright 及其 Chromium：

```sh
node tools/test_web_console.cjs
```

使用本机 Microsoft Edge 时设置 `PHONEUSE_BROWSER_CHANNEL=msedge`。该测试使用模拟手机 HTTP 响应，不证明实体设备行为。

## 模拟器测试

请使用专用模拟器。测试页和设备测试会临时调整无障碍设置并操作前台界面，不要指向正在使用的手机。

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Gradle 任务会先安装仅含 debug 变体的测试页。报告位于 `app/build/reports/androidTests/connected/debug/`。独立场景和定向运行见[测试页指南](../fixture/README.zh-CN.md)。正常结束时恢复设置，测试中断后可能需要手动恢复。

## 验证覆盖范围

| 层次 | 覆盖 | 边界 |
| --- | --- | --- |
| JVM 测试 | 授权、队列、请求 ID、取消、观察及目标校验逻辑 | 假设备执行器不验证 Android 输入 |
| Python / 启动器测试 | 配对、设备路由、异常、MCP、配置、独立分发包 | 模拟端点不能替代多手机联测 |
| Web 测试 | 配对界面、恢复、API 表单、动作 ID、响应式布局 | 使用模拟 HTTP 响应 |
| Android 设备测试 | 生产服务、无障碍、测试控件、界面语言及持久化 | 模拟器不能证明厂商兼容性 |

## 实体验收清单

记录设备型号、Android 版本、应用修订、延迟、错误码及恢复步骤。Android 11–12 语言持久化、Android 14+、Android 17+ 权限与厂商后台限制仍需对应设备验证。

1. 安装、授予无障碍和通知权限、验证服务自动启动，并在适用系统验证局域网权限。分别从 Web 与 MCP 配对，核对两端核对码，测试批准、拒绝、过期和撤销。
2. 未认证 API/MCP 必须失败，浏览器请求应遵守 Host/Origin 检查，根页面保持可访问。配对不能允许远程批准 Phone Use 的受保护控制。
3. 分别观察树、截图和两者，对比系统栏、键盘显隐及旋转时的边界和坐标。受保护截图、密码字段与树可用性分别检查。
4. 启动应用，显式聚焦输入框，重新观察后替换和插入中文，再独立验证实际文字和选区。覆盖空框与不支持的编辑器。
5. 验证节点和坐标点击、长按、滚动、完整滑动路径、返回/主页/最近任务；确认实际命中目标，没有自动降级或重复输入。
6. 改变所选目标、节点身份、显示几何和服务实例，旧引用必须拒绝；普通无关刷新不应要求整页稳定。单独验证显式 `expected_window_id`。
7. 相同 `request_id` 重复提交只能输入一次；不同参数复用同 ID 应冲突。断网后查询原请求，不在保留期外或进程重启后推断可安全重放。
8. 排队动作后在手机撤销客户端，其队列输入不得执行，已注入手势可能完成；撤销一个客户端不影响另一个客户端队列。服务销毁后所有尚未执行输入均应取消。
9. 默认动作不采集动作后树或截图。对 `launch_app` 验证前台确认、五秒超时的 `RESULT_UNKNOWN`、权限提示和 Intent 发出后的取消；其他动作不隐式等待。验证显式条件、重复目标、超时和输入成功后观察失败；执行事实、观察状态与业务成功必须分离。
10. 首次安装及保留旧版停止／暂停设置时打开 App，均应自动监听 8443 端口；服务销毁后重开 App 应恢复服务并保留配对。首页与通知栏不应出现启动、暂停或停止开关。设置中的后台保活默认开启；关闭后重建页面保持关闭，切换其他应用保留当前会话，移除最近任务停止控制服务，开机／更新／无障碍重连不应恢复服务。重新开启后验证任务移除、系统回收和开机恢复；强行停止仍需手动重开。检查电池豁免返回后的状态刷新、厂商入口回退、中英文布局；厂商自启动和省电策略需实机验证。
11. 同一桥接连接实体机和模拟器，检查明确 ID、独立调用、单机离线，以及地址指向其他安装时不发送旧凭证。跨客户端和服务实例的观察引用必须拒绝。
12. 验证动态列表、连续截图、键盘、浮窗和至少十分钟后台运行。记录失败与恢复，不将模拟器结果推广到所有手机。

## 文档检查

英文与简体中文页面成对维护并互相链接。API 名称、JSON 键、命令参数和示例 ID 不随语言改变。接口参考或分发指南变更后运行 Skill 同步及打包检查，不把历史通过数量写成当前运行结果。
