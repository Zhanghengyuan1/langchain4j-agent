# LangChain4j Agent：Tool 调用外部 API

在原有 Java 17 / Spring Boot 3.3.5 / LangChain4j 1.21.0 项目上增加真实 API 工具。

- `getWeather(city)`：用 Open-Meteo 查询城市当前天气，替换原先的模拟数据。
- `googleSearch(query, maxResults)`：通过 Serper 查询 Google 搜索结果，返回标题、链接、摘要。
- 沿用现有同步聊天、SSE、WebSocket 和 Qwen / DeepSeek 模型选择。
- 新代码附中文解释注释；包含 API 契约、错误分支、Agent 回路与 Spring 装配测试。

**学习入口：[Tool + API 代码阅读与实践](docs/TOOL_API_GUIDE.md)。** 文档包含逐文件阅读顺序、调用时序、密钥配置、运行方法、断点与生产边界。

运行要求：JDK 17、Maven、可用的现有模型服务。搜索还需要独立的 `SERPER_API_KEY`；未配置时仍能启动并使用其他工具。

```powershell
mvn test
mvn spring-boot:run
```

打开 [本地聊天页面](http://localhost:8080/)。默认测试不访问公网或模型服务；真实天气测试需要按学习文档显式启用。

本项目目前采用单例聊天历史，适合本地单用户学习。新增 API 层具备基本工程边界，整个项目尚不是可直接开放给多用户的生产系统。
