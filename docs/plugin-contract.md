# 插件契约

权威说明在 [dynamic-bot-core PLUGIN_DEVELOPMENT.md](https://github.com/Colter23/dynamic-bot-core/blob/main/PLUGIN_DEVELOPMENT.md)。这里只记本仓库必须守住的子集。core 升级后以 upstream 为准，并在 [decisions.md](decisions.md) 记一笔。

当前坐标：`top.colter.dynamic:dynamic-bot-core:0.0.4`，`plugin.yml` 的 `apiVersion: 3.0.0`。

## 一期要实现的接口

| 接口 | 作用 |
| --- | --- |
| `PublisherSourcePlugin` | 来源插件身份；轮询后 `sourceUpdatePublisher.publish` |
| `PublisherLookupPlugin` | 用外部用户 ID 取 `PublisherInfo` |
| `PublisherLoginProvider` | Cookie 登录、检查登录态；一期可不做二维码 |
| `ConfigurablePlugin` | 后台配置表单，文案用中文 |
| `LinkResolver` | 一期宜做；匹配并解析小红书链接 |

可以后做：`PublisherFollowPlugin`、`PublisherBatchLookupPlugin`、`PublisherLatestUpdateProvider`、`LinkVideoDownloader`、`PluginAdminPageProvider`。

不要实现 `MessageSinkPlugin`。

## 生命周期

`onLoad` → `onStart` → `onStop` → `onUnload`。

网络、定时轮询、长任务必须挂在 `PluginContext` 的 `CoroutineScope` 或 `TaskScheduler` 上，以便主程序停插件和热重载。

## 发布动态

```kotlin
val result = context.sourceUpdatePublisher.publish(
    SourceUpdatePublishRequest(
        sourcePlugin = context.pluginId,
        update = update,
    )
)
if (result.accepted) {
    // 才能推进游标或写入已处理状态
}
```

| 结果 | 含义 | 游标 |
| --- | --- | --- |
| `ENQUEUED` | 主程序已建投递 | 可推进 |
| `DUPLICATE` | 见过了 | 可推进 |
| `IGNORED` | 无目标 / 被过滤 / 空内容 | 可推进 |
| `FAILED` | 主程序没可靠收下 | **不可**推进 |

`accepted` 为真当且仅当结果不是 `FAILED`。不要用事件总线投递动态。

有订阅时读 `subscriptionQueryService` 的 `PublisherSubscribers.subscriptions` 和 `SubscriptionPolicy`，不要只看“有没有订阅者”。笔记轮询看 `DYNAMIC`，直播轮询看 `LIVE_STARTED` / `LIVE_ENDED`。直播状态用 `sourceStateStore.findLatestLiveStatus` / `saveLiveStatus`；`FAILED` 时不得覆盖旧状态。

## 动态内容

按平台展示顺序往 `DynamicPayload.blocks` 里放块：

- 文字：`TextBlock(DynamicContent(...))`
- 图片组：`ImageGridBlock`
- 视频 / 链接卡：`MediaCardBlock(..., style)`，`style` 必须由本插件给出（`LARGE` / `SMALL` / `MINI`）
- 转发或引用：`RepostBlock`
- 附加信息：块放末尾，`role = ADDITIONAL`

不要把正文、图片、视频拆成互不相关的平行列表交给主程序猜顺序。

## 配置与私有数据

- 用户能改的：`ConfigService` + `ConfigurablePlugin`。
- 游标、缓存、映射：`PluginDataStore`。
- 不要把游标塞进配置表单。
- 表单 label、section、校验错误、用户可见提示用中文。

## 禁止依赖

插件（含测试）不得直接 import：

- `top.colter.dynamic.core.repository.*`
- `top.colter.dynamic.core.table.*`
- `top.colter.dynamic.repository.*`
- `top.colter.dynamic.table.*`
- `top.colter.dynamic.plugin.*`（宿主加载器，不是 `core.plugin`）
- `top.colter.dynamic.event.*`

需要来源游标或订阅快照时，只用 `PluginContext.sourceStateStore` 和 `subscriptionQueryService`。
