# GitHub Copilot Review Instructions

请用中文给出 PR Review 意见，优先指出会影响生产稳定性、数据安全、计费准确性或用户体验的问题。不要只做格式化建议；如果没有高风险问题，请明确说明剩余风险和测试缺口。

后端项目背景：
- Java 17 + Spring Boot 3.2 + Spring AI 1.0.0。
- PostgreSQL 保存用户、会话、消息、RAG 元数据、模型、价格和 usage event。
- Redis 用于短期记忆、摘要缓存和并发锁。
- Milvus 用于 RAG 向量检索。
- S3/SQS 用于文档上传和异步摄取。
- 模型调用必须经过 `AiChatModelGatewayService`，不要绕过计费、配额和审计。

重点审查规则：
1. 模型调用：所有聊天调用都必须保留 userId/sessionId/requestId/source 上下文，并记录成功、失败、超限和缺价格事件。
2. 计费配额：新增模型、Provider、价格或 usage 逻辑时，检查 Token 统计、价格快照、异常路径和幂等性。
3. RAG 安全：检索必须按 ownerFolder、sessionId、chatId、fileIds 做隔离，不能跨用户或跨会话召回。
4. 文件摄取：S3 objectKey、文件大小、扩展名、状态流转和失败重试必须有边界控制。
5. 工具系统：MCP URL 要防 SSRF，CLI 命令要走 `ToolGuard` / `SandboxedCommandRunner`，工具上下文不能由模型伪造。
6. 数据库迁移：新增实体字段必须配套 Liquibase YAML changelog，避免直接依赖 Hibernate 自动建表。
7. 并发与流式：SSE、异步摘要、SQS listener 和缓存重建要检查线程池、超时、锁和资源释放。
8. 测试：高风险业务变更应补 JUnit/Mockito 测试；需要基础设施的测试不要放入 `ci` profile。

输出格式：
- 先列问题，按严重程度排序，并引用文件与行号。
- 每个问题说明影响、触发条件和建议修复方式。
- 最后给出测试建议或“未发现阻塞问题”。
