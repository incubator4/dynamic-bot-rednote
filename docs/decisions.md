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

In：Cookie / 扫码登录（ADR-0010）、用户 ID 查资料、笔记轮询、`DynamicPayload` 映射、游标、风控暂停、配置表单；宜做链接解析。

Out：用户名搜索、自动关注、视频下载、插件独立后台页、出口协议、第二套推送通道。

订阅键使用小红书用户 ID。用户名 / 小红书号搜索另开 ADR。直播开播/下播见 [ADR-0009](#adr-0009-直播开播与下播订阅)。扫码登录见 [ADR-0010](#adr-0010-支持小红书网页扫码登录)。

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
| 轮询节奏 | 状态轮询间隔 ≥1s；二维码有效期约 4 分钟，超时返回 `EXPIRED`（对齐 [xiaohongshu-cli](https://github.com/jackwener/xiaohongshu-cli)） |
| 中间态 | 未扫码 / 已扫码待确认映射为 `PublisherLoginStatus.PENDING` |
| 失败 | 风控、签名失败、接口异常返回中文 `FAILED` / `EXPIRED`，不加密重试、不绕过校验 |

不做：开放平台 OAuth `app_id`/`app_secret` 设备授权（与现有网页 Cookie 会话模型不兼容）、短信验证码登录、自动刷新过期 Cookie。

Cookie 登录与登录失效暂停轮询仍按 ADR-0004 / ADR-0005 保留。

## ADR-0011: 数据接口使用 XYW_ 请求签名

- Status: Superseded by ADR-0012
- Date: 2026-09-20
- Related: [Cloxl/xhshow#104](https://github.com/Cloxl/xhshow/issues/104)、[PR #105](https://github.com/Cloxl/xhshow/pull/105)

小红书 edith 的数据拉取接口（`user_posted`、`user/otherinfo`、`feed` 等）在约 2026-03 后会拒绝旧签名格式并返回 HTTP 406。扫码登录等非数据接口仍可用既有签名。

| 项 | 约定 |
| --- | --- |
| 数据接口 | `user_posted` / `otherinfo` / `feed` 请求带 `X-s`=`XYW_…`、`X-t`、`X-S-Common` |
| 算法来源 | 对齐开源 [xhshow](https://github.com/Cloxl/xhshow) v0.2.0 的 `sign_xyw`（AES-128-CBC） |
| 签名串 | GET 用 path+query（逗号不编码，与 xhshow `_build_content_string` 一致）；POST 用 path+请求体原文 |
| 非数据接口 | 原约定二维码 create/status 用 legacy 签名；已被 [ADR-0013](#adr-0013-对齐-xiaohongshu-cli-的-cookie-与扫码登录) 取代，登录相关接口改走 XYW_ |
| 失败 | HTTP 406 仍按现有路径报中文错误并暂停重试，不加密绕过 |

原“不做：完整移植 xhshow 的 `XYS_` / `x-rap-param` / 设备指纹 `b1` 流水线”已被 ADR-0012 取代：仅 `feed` 等风控接口补齐 `x-rap-param`，`X-s-common` 对齐 xhshow 新模板并接入 `b1` 指纹，`XYS_` 与搜索接口仍不实现。

## ADR-0012: 对齐 xhshow 的 X-S-Common 模板、b1 指纹与 x-rap-param

- Status: Accepted
- Date: 2026-09-20
- Related: ADR-0011、[Cloxl/xhshow](https://github.com/Cloxl/xhshow)（`core/xrap.py`、`generators/fingerprint.py`、`utils/hash.py`）

ADR-0011 只补了 `X-s=XYW_…`，实测 `user_posted` / `otherinfo` / `feed` 仍返回 HTTP 406。根因是服务端还校验 `X-S-Common` 模板版本、设备指纹 `b1`、链路追踪头，以及 `feed` 等风控接口的 `x-rap-param`。本 ADR 在不扩大产品范围的前提下补齐这些头。

| 项 | 约定 |
| --- | --- |
| `X-S-Common` | 对齐 xhshow 新模板：`x1=4.3.5`、`x4=4.86.0`、`x6/x7` 留空、`x8=b1`、`x9=crc32_js(b1)`、`x10=0`、`x11=normal`；`x5` 仍为 cookie `a1`。编码用自定义 base64（`customBase64Encode`）。 |
| `b1` 指纹 | 新增 `RednoteFingerprint`：采样一份稳定的 PC 浏览器指纹子集，ARC4（密钥 `xhswebmplfbt`）加密后按 xhshow 的 latin1+quote+base64 路径编码。仅覆盖 `generate_b1` 实际消费的字段，不完整复刻全量指纹。 |
| 追踪/分片头 | 所有 XYW_ 请求额外带 `x-b3-traceid`（16 hex）、`x-xray-traceid`（32 hex，前 16 位编码时间戳+序号）、`x-mns=unload`、`xy-direction`（有 `userId` 时用 MurmurHash3 分片，否则随机 10..100）。 |
| `x-rap-param` | 新增 `RednoteXrap` + `RednoteXrapCipher`：TLV body → gzip（OS 字节改 `0x03`）→ 异或 → SM4 变种 ECB → 36 字节信封 + base64。`feed` POST 带 `x-rap-param`，`api` 用 `//edith.xiaohongshu.com/api/sns/web/v1/feed`。SM4 轮密钥/S-box/xxh32 均按 xhshow 纯 Python 实现移植。 |
| 接入点 | `RednoteClient.sendXywSignedGet/Post` 统一应用上述头；`enrichNote` 调用 feed 时传 `xRapApi`。`fetchPublisherSnapshot` / `fetchUserNotes` / `fetchLiveSnapshot` 把目标 `userId` 透传给签名，用于 `xy-direction` 分片。 |
| 仍不做 | `XYS_` 旧签名、搜索/关注/视频下载等未立项接口；不绕过登录态或风控校验，406 仍按既有路径报中文错误并暂停。 |
| 风险 | `b1` 为简化指纹，若服务端后续强校验完整指纹可能再次 406；届时再按 xhshow 全量字段补齐。 |

## ADR-0013: 对齐 xiaohongshu-cli 的 Cookie 与扫码登录

- Status: Accepted
- Date: 2026-09-20
- Related: ADR-0004、ADR-0010、ADR-0011、[jackwener/xiaohongshu-cli](https://github.com/jackwener/xiaohongshu-cli)
- Supersedes: ADR-0010 中「二维码有效期约 3 分钟」；ADR-0011 中「二维码 create/status 继续用 legacy 签名」

Cookie 登录和网页扫码登录对齐 [xiaohongshu-cli](https://github.com/jackwener/xiaohongshu-cli) 的 HTTP 流程（`xhs_cli/qr_login.py`、`AuthEndpointsMixin`），不引入浏览器自动化。

| 项 | 约定 |
| --- | --- |
| Cookie 必要字段 | 导入或校验时至少要有 `a1` 和 `web_session`；缺少时返回中文失败，不发请求 |
| Cookie 别名 | QR / activate 返回的 `session` → `web_session`，`secure_session` → `web_session_sec`；导入时 `secure_session` 规范成 `web_session_sec` |
| 登录态检查 | `GET /api/sns/web/v2/user/me` 走 XYW_ 签名，不再发未签名请求 |
| 扫码流程 | 游客 `a1`/`webId` → `POST /login/activate`（失败不阻断）→ `POST /login/qrcode/create`（`qr_type=1`）→ 轮询 `POST /api/qrcode/userinfo`（`service-tag: webcn`）→ 确认后再 `GET /login/qrcode/status` 取会话 |
| 扫码会话隔离 | 扫码开始时不用旧配置 Cookie，避免游客会话和旧账号混用 |
| 完成重试 | 确认后最多 5 次、间隔 ≥1s，直到完成接口或 `user/me` 的用户与确认用户一致 |
| 有效期 | 轮询超时 240s，与 CLI 一致 |
| 签名 | 上述登录接口与 `user/me` 一律 XYW_，不再对扫码走 legacy `X-s` |
| 仍不做 | 不引入 Camoufox/Playwright 浏览器扫码；不绕过风控或登录态；Cookie 自动刷新仍不是 MVP |
