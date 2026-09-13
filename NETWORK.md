# LiveRecorder Network / 艾尔岚群组直播版

基于 [httye/LiveRecorder](https://github.com/httye/LiveRecorder) 的 MIT 分支。
原作者的版权与单服实现保留；群组协调与通信由 EllanStudio 分支新增。

**本版本为预发布版本。已完成本地编译和自动化测试，尚未在艾尔岚实际三服、
AuthMe、InvSync、CarbonChat 组合下进行客户端端到端验收。不要直接开启公开直播。**

## 实现范围

- Velocity 统一管理直播号、目标、自动随机/顺序轮播。
- 目标在白名单服务器间移动时，自动协调直播号换服，等待新服就绪后继续跟拍。
- 全服唯一的隐私授权库：默认未授权，不跟拍；玩家授权、拒绝、撤回均全服有效。
- 断线或后端失联时暂停镜头；后台状态超时上限约 4 秒，随后回等待位置。
- 每个专用直播号可独立绑定目标；绑定、启用状态及隐私设置可重启恢复。
- 通过现有 Minecraft 插件消息连接通信，不增加监听端口，不需要 Redis 或 MySQL。
- 群组模式只使用代理的 network.db，不读写后端旧版 privacy.db。

这不是 OBS 或推流服务：仍需要一个真实登录的 Minecraft 客户端作为直播号，
由 OBS 等软件采集并推流。换服仍可能出现客户端加载画面，不能保证无缝视频切换。
此分支不提供机器人账号登录、无人值守客户端重连或 OBS 场景自动切换。

## 两个安装包

| 安装位置 | 文件 |
| --- | --- |
| Velocity 的 plugins | LiveRecorder-Velocity-1.1.0-ellan.jar |
| Spawn / Survival / Redstone 的 plugins | LiveRecorder-1.1.0-ellan.jar |

不要把两个 JAR 都放进同一服务器。后端 JAR 替换旧 LiveRecorder，不要并存。
后端默认仍是原版单服模式，必须显式打开 network.enabled。

代理以 Velocity 3.4 API / Java 17 编译，后端以 Bukkit 1.16.5 API / Java 8 编译。
实际运行时需满足你所用 Velocity、Paper/Leaf 的 Java 要求。
本分支未验证 Folia，也不保证上游全部旧版本兼容。编译通过不等于完成 26.2 实机验收。

## 配置步骤

1. 停止直播客户端的公开推流。备份插件与配置。
2. 在测试代理和后端安装对应 JAR，正常重启生成配置。
3. 给每个后端准备一个封闭的直播等待房间，并记录世界名和坐标。
4. 配置代理生成的 plugins/liverecorder-network/network.properties：

```properties
# 首次启动会自动生成随机 secret。不要把真实密钥提交到仓库。
secret=此处保留系统生成的密钥
# 名称必须与 velocity.toml 的服务器注册名称完全一致，区分大小写
servers=spawn,survival,redstone
# 专用直播号在此群组中实际使用的 UUID；多个用逗号分隔
recorders=00000000-0000-0000-0000-000000000001
switch-seconds=60
rotation=RANDOM
```

5. 在三服的 plugins/LiveRecorder/config.yml 配置：

```yaml
network:
  enabled: true
  secret: "与代理完全一致的随机密钥，至少32个字符"
  recorder-uuids:
    - "00000000-0000-0000-0000-000000000001"
  join-delay-ticks: 100
  waiting-world: "world"
  waiting-x: 0.5
  waiting-y: 100.0
  waiting-z: 0.5
  hide-chat: true
```

示例 UUID 不是真实账号。三服的 UUID 名单和密钥必须与代理一致。
waiting 坐标应改成各自实际封闭房间的位置，插件不会自动创建房间。
以群组实际转发的 UUID 为准，离线模式不能直接套用网上查到的正版 UUID。

6. 完成配置后正常重启相关代理与后端，再登录专用直播号。
7. 普通玩家使用 /lr accept 授权；管理员启用自动轮播或固定绑定。
8. 验收通过后再打开 OBS 公开推流。

代理侧初次配置的直播号默认暂停，需首次执行启用命令。
后续重启恢复已保存的启用状态；直播号不在线时不会自动创建或登录账号。

## 命令与权限

所有游戏内群组命令由 Velocity 处理，别名 /lr：

| 命令 | 功能 |
| --- | --- |
| /lr accept | 同意全服直播 |
| /lr decline | 拒绝/撤回全服直播授权 |
| /lr privacy | 查询自己的全服授权 |
| /lr setprivacy unset | 清除自己的授权，恢复默认不跟拍 |
| /lr mode Camera auto | 启用自动轮播，遍历三服已授权且就绪的玩家 |
| /lr bind Camera Player manual | 固定跟拍 Player，跟随其跨服 |
| /lr bind Camera Player auto | 先跟拍 Player，再按周期自动切换 |
| /lr switch Camera Player | 更换目标，保留当前模式 |
| /lr unbind Camera | 暂停这个直播号，回等待位置 |
| /lr list | 查看直播号及其目标/模式 |
| /lr logs | 查看最近 10 条授权/管理记录 |

普通玩家只能修改自己的授权。管理命令需要 **Velocity 侧**
liverecorder.admin 权限；只有后端 OP 不一定拥有此权限。无需给直播号 OP。
管理员撤不掉别人的隐私限制；未授权的绑定只会发出授权提示，玩家同意后需重新绑定。
游戏内命令要求后端已报告登录验证完成；代理控制台可以执行管理命令。
自动轮播无需一个个 bind，但参与的玩家必须先主动 /lr accept。

MANUAL 和 SPECTATOR 在此群组版本均表示固定目标的第三人称镜头，
不自动轮播，也不提供自由驾驶或第一人称附身。
AUTO 在目标换服期间最多等待 15 秒，超过则尝试其他已授权目标。
固定模式下目标离线一直等待，目标恢复后继续；拒绝授权立即取消跟拍资格。
白名单之外的服务器不参与轮播，配置的直播号也不能转入这些服务器。

## 与艾尔岚现有插件配合

- AuthMe：通过其 API 检查登录；无法调用 API 时不报告就绪，不猜测玩家已登录。
- InvSync：join-delay-ticks 是保守的加入等待窗口，不是 InvSync 完成事件。
  需实测库存/位置同步耗时，必要时加长；最好使用不参与生存玩法的专用直播账号，
  按 InvSync 实际支持的配置排除其位置/游戏模式同步，避免互相覆盖。
- 等待窗口内允许其他插件完成加入传送，之后由直播组件接管镜头位置。
- 隐身玩家会从目标池排除；兼容常见 vanished 元数据标记。
- 直播号使用旁观者模式且对其他玩家隐藏；切换会加载目标附近的客户端区块，
  因此并非零性能开销。建议先用一个直播号，观察 MSPT 与区块数。
- 群组模式复用 camera 的几何与平滑参数，不使用旧单服 auto-switch、
  privacy 和 visual 等配置来决定全服行为。切换周期以代理为准。

## 隐私与安全边界

HMAC 签名、连接随机数与递增序号用于拒绝伪造或重放控制消息。
代理消费专用消息通道，不将客户端构造的控制包转发给后端。
仅接受配置白名单的后端，且按当前玩家连接身份匹配心跳。
仍必须用 Velocity 安全转发及防火墙阻止绕过代理直连后端，并保护共享密钥。

玩家实体在授权确认前对镜头隐藏，镜头只跟拍已授权玩家；这是
**跟拍授权与实体可见性控制，不是完整的视频脱敏系统**。
已授权玩家附近的建筑、告示牌、地图、掉落物、聊天等仍可能出现在视频中。
hide-chat 过滤 Bukkit 标准聊天收件人，CarbonChat、其他插件直接发包、
系统消息、计分板及客户端 MOD 不保证被过滤。公开直播应在客户端关闭聊天，
并使用 OBS 等待场景遮挡登录、换服、同步过程。网络拥塞/服务端停顿也可能延迟
隐私撤回的可见效果，不能承诺所有情况下即时清除已经显示的画面。

不支持热卸载。后端正常禁用时会断开专用直播号，防止失去跟拍限制。
配置错误、未安装后端组件的初始登录画面也不属于可公开直播的安全场景。

## 数据与重载

- 数据保存在代理 plugins/liverecorder-network/network.db。
- 备份 SQLite 时应停代理或使用 SQLite 在线备份，不能只复制正在写入的主文件而忽略 WAL。
- 旧版三服独立 privacy.db **不自动合并**，避免把旧同意覆盖新拒绝；
  初次迁移需要玩家重新选择，默认一律不跟拍。
- 密钥、账号名单、开关、代理周期/服务器列表修改后正常重启对应服务。
- 后端控制台 lr reload 只刷新镜头、等待坐标等可热改配置。
- 不要用 PlugMan 或服务端 /reload 热重载整个插件，连接随机数需要重新握手。

## 上线验收

1. 未授权普通玩家不被自动选中，直播号停在房间。
2. 玩家授权后，在每个子服 /lr privacy 都显示同一结果。
3. 固定跟拍从 Spawn 到 Survival，再到 Redstone，镜头经过等待后追上。
4. 自动轮播在同服/跨服目标间切换；没有目标时停在房间。
5. 玩家 /lr decline 后停止被跟拍，重登和换服不能恢复旧授权。
6. 目标断线、相机断线重连、死亡重生、隐身、代理重启、后端失联均正确暂停/恢复。
7. 未完成 AuthMe 登录的玩家不能授权或执行管理员命令。
8. 核实 InvSync 不反复覆盖直播号位置/模式，以及 CarbonChat 和 HUD 不泄露敏感信息。
9. 白名单之外的 Test 不能作为直播号目的地，旧连接消息不能恢复镜头。
10. 先录制本地样片检查加载画面、镜头与性能，再公开推流。

## 构建

```sh
mvn -B clean verify
mvn -B -f velocity/pom.xml clean verify
```

用 JDK 21 + Maven 3.9 构建。两个 target 下分别生成已包含 SQLite 驱动的 JAR，
不要安装 original- 前缀的未打包 JAR。GitHub Actions 同时测试、构建并上传两个组件。
