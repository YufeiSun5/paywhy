# 主要依赖

版本在 Gradle 构建文件中固定。发布前应对最终依赖树及许可证做一次复核。

| 依赖 | 用途 | 上游许可证 |
|---|---|---|
| Kotlin / kotlinx.coroutines / kotlinx.serialization | 语言、异步、纯文本 JSON 编解码 | Apache-2.0 |
| AndroidX Activity / Compose / Lifecycle / Room / WorkManager | Android UI、本地数据库、后台调度 | Apache-2.0 |
| [AppAuth-Android](https://github.com/openid/AppAuth-Android) | 浏览器 OAuth、PKCE、令牌刷新 | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | HTTPS 发信 | Apache-2.0 |
| JUnit / Robolectric | 仅测试 | EPL-1.0 / MIT |

源码中没有复制调研项目的代码；参照的是 Android / OAuth 官方 API 行为。
