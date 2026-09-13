# LiveRecorder Network 1.1.0-ellan（预发布）

基于 httye/LiveRecorder 的 MIT 群组分支。

- Velocity 统一维护隐私授权及直播会话，不需要 MySQL/Redis。
- 白名单服务器间自动跟拍与随机/顺序轮播。
- 专用直播号、登录就绪握手、跨服暂停、授权撤回及消息认证。
- 保留单服模式；新增两组件构建与自动化测试。

安装：Velocity 使用 LiveRecorder-Velocity-1.1.0-ellan.jar，
三个后端使用 LiveRecorder-1.1.0-ellan.jar，不能放反。
先按 NETWORK.md 配置同一密钥、直播号 UUID、服务器名单和等待房间。

本地 JDK 21 编译和自动化测试已通过；尚未完成实际 Minecraft 客户端、
三服、AuthMe、InvSync 的联调。未安装或重启生产服务器。
直播客户端与 OBS 仍需另行准备，不能保证换服画面无缝或完整视频隐私脱敏。
