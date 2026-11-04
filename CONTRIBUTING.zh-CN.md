# 参与 Phone Use

[English](CONTRIBUTING.md) | **简体中文**

## 报告问题

请提供应用修订、设备型号和 Android 版本、客户端/宿主版本、复现步骤、预期与实际行为、相关错误码。区分模拟器和实体设备结果。输入结果不确定时记录原请求状态，不要仅为复现而重放动作。

报告中移除凭证、配对秘密、私人屏幕内容和本机路径，不附上 `~/.phoneuse.json` 或真实账号数据。本项目采用 [MIT 许可证](LICENSE)。

如果怀疑存在安全漏洞，请按[安全策略](SECURITY.md)使用私密报告流程，不要创建包含漏洞细节的公开 issue。

## 提交修改

克隆或拉取后先运行 `git submodule update --init --recursive` 初始化 Skills 子模块，编辑前运行 `git -C skills switch main`。先将 Skill 修改推送到 [phone-use-skills](https://github.com/nidhoggfgg/phone-use-skills)，再在主仓库提交更新后的 `skills` 指针。详见[维护流程](docs/integrations.zh-CN.md#维护与离线包)。

保持修改聚焦，说明解决的问题及最终行为。行为变更时补充适当测试，尤其关注授权、请求去重、目标校验和执行/观察状态。涉及无障碍设置或屏幕操作的测试使用专用模拟器。命令和验证边界见[开发与验证](docs/validation.zh-CN.md)。

Pull request 中说明改了什么、如何验证、还有什么未验证。区分 Android 接受操作与应用任务实际成功，不将假执行器或模拟 HTTP 测试当作实机覆盖。

## 文档与翻译

- `README.md` 和普通指南路径默认英文，简体中文使用同目录的 `*.zh-CN.md`。标题下提供双向语言链接，内部导航保持当前语言。
- 行为或安装方式变化时同步更新两种语言。API/工具名称、JSON 键、参数、路径和示例标识保持不变。
- `docs/api.md` 与 `docs/api.zh-CN.md` 是协议参考源文件。修改后运行 `python tools/sync_skill.py`，先在子模块中提交 `skills/phone-use/references/` 的同步副本，再更新主仓库的子模块指针。
- `skills/phone-use/SKILL.md` 是供 AI 读取的英文入口。中文对照为不含 Skill frontmatter 的 `README.zh-CN.md`，避免安装器发现重复 Skill。
- 增加分发文档时按需更新 `tools/export_integrations.py` 和 npm `files` 清单，检查仓库及解压包中的链接。

## 目录说明

| 路径 | 用途 |
| --- | --- |
| `app/` | Android App、无障碍服务、HTTP/MCP 服务、Web 控制台 |
| `tools/phoneuse_client.py` | Python CLI 与 stdio 桥接源文件 |
| `bin/phone-use.mjs` | Node 启动器及宿主配置输出 |
| `skills/` | `phone-use-skills` Git 子模块，内含可独立安装的 `phone-use/` Skill |
| `integrations/mcp/` | 离线 MCP 配置生成器与说明 |
| `fixture/` | 仅 debug 的 Android 测试场景 |
| `docs/` | 使用、接入、协议及验证指南 |
