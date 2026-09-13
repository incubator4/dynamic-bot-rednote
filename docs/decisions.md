# Decisions

按时间追加。改方向时先改本文件，再改代码。状态：`Accepted` / `Superseded` / `Proposed`。

## ADR-0001: 本仓库只做单一来源插件

- Status: Accepted
- Date: 2026-09-13

`dynamic-bot-rednote` 是一个 Kotlin fatJar 产品，不是宿主、不是出口插件、不是多插件单体仓。交付物是放入主程序 `plugins/` 的 `*-all.jar`。

## ADR-0002: 骨架对齐微博官方插件

- Status: Accepted
- Date: 2026-09-13

一期以 [dynamic-bot-weibo](https://github.com/Colter23/dynamic-bot-weibo) 为模板：`Plugin` 薄入口 + `Runtime` 编排 + `Gateway` + `Mapper` + `CursorStore` + `RequestFailureHandler`。Bilibili 的搜索、二维码、直播、批量关注留到后续 ADR。

只依赖 [dynamic-bot-core](https://github.com/Colter23/dynamic-bot-core) 公开 API，不依赖主程序内部实现。

## ADR-0003: 平台标识用 `rednote`

- Status: Accepted
- Date: 2026-09-13

| 项 | 值 |
| --- | --- |
| 仓库 / 产品名 | `dynamic-bot-rednote` |
| `plugin.yml` `id` | `rednote-publisher` |
| `PlatformId` | `rednote` |
| Gradle `group` | `com.incubator4.dynamic` |
| 包名 | `com.incubator4.dynamic.rednote` |

对外文案用「小红书」。用户可见配置和错误信息用中文。标识符不用 `xhs` 或 `xiaohongshu`，避免和仓库名分裂。包名跟本仓库 Gradle `group`，不要抄官方插件的 `top.colter.dynamic.*`。

## ADR-0004: 登录以 Cookie 为先

- Status: Accepted（扫码登录见 [ADR-0010](#adr-0010-支持小红书网页扫码登录)）
- Date: 2026-09-13

`PublisherLoginProvider` 一期保证 Cookie 登录、登录态检查、登录失效暂停轮询。二维码登录见 ADR-0010。Cookie 自动刷新仍不是 MVP。

Cookie 只存在用户本机的 `config/`，不进 git，不写进文档示例的真实值。

## ADR-0005: 轮询默认保守

- Status: Accepted
- Date: 2026-09-13

小红书风控敏感，默认对齐微博：

- `pollingEnabled` 默认 `false`，需要推送时再打开。
- 默认轮询间隔不少于 60 秒；默认请求间隔不少于 1 秒。
- 连续未登录或疑似风控时暂停轮询，而不是缩短间隔重试。
- 补发窗口默认 0，首次启动只记游标、不推旧笔记。

检测策略优先「少请求、可暂停」，不要默认对每个订阅用户高频直打个人主页。若后续有更稳的时间线/关注流，再单开 ADR 切换。

## ADR-0006: 一期能力边界

- Status: Accepted
- Date: 2026-09-13

In：Cookie 登录、用户 ID 查资料、笔记轮询、`DynamicPayload` 映射、游标、风控暂停、配置表单；宜做链接解析。

Out：用户名搜索、自动关注、视频下载、插件独立后台页、出口协议、第二套推送通道。

订阅键使用小红书用户 ID。用户名 / 小红书号搜索另开 ADR。直播开播/下播见 [ADR-0009](#adr-0009-直播开播与下播订阅)。

## ADR-0007: 构建与 API 版本

- Status: Accepted
- Date: 2026-09-13

- `compileOnly` `top.colter.dynamic:dynamic-bot-core:0.0.4`
- `plugin.yml` `apiVersion: 3.0.0`（与当前 `CORE_PLUGIN_API_VERSION` 对齐）
- Java 17 字节码，Gradle toolchain 21
- 官方 fatJar 脚本：不打包宿主已提供的 logging / core
- 本地若有 `../dynamic-bot-core`，composite build

升级 core 时同步改本 ADR 的版本号，并跑官方插件同风格的边界测试（禁止 import 宿主内部包）。

## ADR-0008: Kotlin 包名跟随 Gradle group

- Status: Accepted
- Date: 2026-09-13

本仓库 Maven / Gradle `group` 是 `com.incubator4.dynamic`。Kotlin 源码、测试和 `plugin.yml` `mainClass` 使用 `com.incubator4.dynamic.rednote`。

不要使用官方插件风格的 `top.colter.dynamic.rednote`。`dynamic-bot-core` 依赖坐标仍是 `top.colter.dynamic:dynamic-bot-core`。生成的 `GitVersion.kt` 放在 `group` 包 `com.incubator4.dynamic`。

## ADR-0009: 直播开播与下播订阅

- Status: Accepted
- Date: 2026-09-13
- Supersedes: ADR-0006 中「不做直播」的边界

在已有笔记订阅之外，支持小红书直播的开播 / 下播提醒。对齐 [dynamic-bot-bilibili](https://github.com/Colter23/dynamic-bot-bilibili) 的直播状态模型，而不是另做独立直播源插件。

| 项 | 约定 |
| --- | --- |
| 订阅键 | 仍用小红书用户 ID，不另引入直播间 ID 作为发布者 |
| 事件 | `live.started` / `live.ended`，载荷为 `LivePayload` |
| 谁会被轮询 | 仅 `SubscriptionPolicy` 启用了 `LIVE_STARTED` 或 `LIVE_ENDED` 的发布者 |
| 检测方式 | 复用已有用户资料接口读取直播态；没有批量直播接口，不额外发明高频轮询 |
| 启动 | 先记下当前开播/未开播状态，不把「已经在播」当成新开播补发 |
| 游标 | 直播状态走 `sourceStateStore.saveLiveStatus`；发布 `FAILED` 时不得覆盖旧状态 |
| 配置 | `liveDetectionEnabled` 默认开启；仍受 `pollingEnabled` 与 ≥60s / ≥1s 间隔约束 |

不做：直播流下载、弹幕、回放、按直播间 ID 搜索、扫全站正在直播列表。

## ADR-0010: 支持小红书网页扫码登录

- Status: Accepted
- Date: 2026-09-13
- Supersedes: ADR-0004 中「二维码登录不是 MVP」；ADR-0006 Out 中的「二维码登录」

在 Cookie 登录之外，支持 `PublisherLoginMethod.QR_CODE`：对齐 [dynamic-bot-bilibili](https://github.com/Colter23/dynamic-bot-bilibili) 的 `loginByQrCode` 契约，方便用户在 Web 后台用手机小红书 App 扫码登录。

| 项 | 约定 |
| --- | --- |
| 能力声明 | `supportedLoginMethods = {COOKIE, QR_CODE}` |
| 流程 | 创建二维码 → `onQrCode(PublisherQrLoginChallenge)` → 轮询状态并 `onStatusChanged` → 成功后写入 Cookie 并 `checkLoginState` |
| 凭证落盘 | 扫码成功得到的会话 Cookie 写入 `ConfigService`（与 Cookie 登录同一字段），不进 git |
| 轮询节奏 | 状态轮询间隔 ≥1s；二维码有效期约 3 分钟，超时返回 `EXPIRED` |
| 中间态 | 未扫码 / 已扫码待确认映射为 `PublisherLoginStatus.PENDING` |
| 失败 | 风控、签名失败、接口异常返回中文 `FAILED` / `EXPIRED`，不加密重试、不绕过校验 |

不做：开放平台 OAuth `app_id`/`app_secret` 设备授权（与现有网页 Cookie 会话模型不兼容）、短信验证码登录、自动刷新过期 Cookie。

Cookie 登录与登录失效暂停轮询仍按 ADR-0004 / ADR-0005 保留。
