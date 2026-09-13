# 架构

本插件是 dynamic-bot 进程内的 JVM 插件。主程序从运行目录 `plugins/` 加载 fatJar，按 `plugin.yml` 的 `mainClass` 实例化，再按实现的接口发现能力。

## 在体系里的位置

```text
小红书 Web / 接口
        │
        ▼
本插件（登录、轮询、映射、可选链接解析）
        │ sourceUpdatePublisher.publish(...)
        ▼
dynamic-bot 主程序（订阅、过滤、绘图、outbox、重试）
        │
        ▼
出口插件（如 dynamic-bot-onebot）
```

不要另开 HTTP 服务、不要自己发 QQ、不要走内部事件总线投递动态。

## 对齐官方插件

优先抄 [dynamic-bot-weibo](https://github.com/Colter23/dynamic-bot-weibo) 的拆分，而不是 Bilibili：小红书一期同样是 Cookie 登录、无直播、轮询要保守。

| 职责 | 官方对应 | 本仓库目标 |
| --- | --- | --- |
| 入口，只转发接口 | `*PublisherPlugin` | `RednotePublisherPlugin` |
| 生命周期与业务编排 | `*PublisherRuntime` | `RednotePublisherRuntime` |
| 用户可见配置 + 中文表单 | `*PublisherConfig` | `RednotePublisherConfig` |
| 平台 HTTP | `WeiboGateway` / `WeiboClient` | `RednoteGateway`（或拆 Client） |
| 平台游标 | `*CursorStore` | `RednoteCursorStore`（走 `dataStore`，不要写进配置） |
| 平台 JSON → `DynamicPayload` | `*DynamicMapper` | `RednoteDynamicMapper` |
| 登录失效 / 风控 | `*RequestFailureHandler` | `RednoteRequestFailureHandler` |
| 链接解析 | `*LinkResolver` | `RednoteLinkResolver`（一期宜做） |

插件类保持薄：构造 Runtime，把 core 接口委托出去。可测试逻辑放 Runtime / Mapper / Handler。

## 建议包与资源

```text
src/main/kotlin/top/colter/dynamic/rednote/
src/main/resources/plugin.yml
src/main/resources/draw/rednote/logo/
src/test/kotlin/top/colter/dynamic/rednote/
```

`plugin.yml` 目标：

```yaml
id: rednote-publisher
mainClass: top.colter.dynamic.rednote.RednotePublisherPlugin
apiVersion: 3.0.0
```

`platformId` 固定为 `rednote`。绘图资源用 `platformId + key` 声明，例如 `logo.primary`、`logo.wordmark`，不要把资源路径写进动态数据。

## 构建

- Kotlin JVM，字节码目标 17，toolchain 21。
- 生产依赖：`compileOnly("top.colter.dynamic:dynamic-bot-core:0.0.4")`。
- 复用官方 `gradle/dynamic-plugin-fatjar.gradle.kts`：fatJar **不要**打进 core、kotlin-logging、slf4j、logback、log4j。
- 若隔壁存在 `../dynamic-bot-core`，在 `settings.gradle.kts` 里 `includeBuild`。
- 交付物：`./gradlew fatJar` 生成的 `*-all.jar`，放到主程序 `plugins/`。

## 数据放哪

| 数据 | 位置 | 原因 |
| --- | --- | --- |
| Cookie、轮询间隔等 | `config/{configId}.yml`（`ConfigurablePlugin`） | 用户可在后台改 |
| 笔记游标、内部缓存 | `data/plugins/{pluginId}/*.yml`（`PluginDataStore`） | 不进配置页 |
| 订阅关系、投递状态 | 主程序 | 插件只读 `subscriptionQueryService` / `sourceStateStore` |

配置变更需要迁移时，按 core 的 `CONFIG_MIGRATION.md` 追加 `ConfigMigration`，不要 silently rename 字段。
