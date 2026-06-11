<div align="center">

# 🦅 Honghu AI

### 企业级多模型智能对话 · RAG 知识库 · 可扩展技能（MCP / Tool-Calling / CLI / Skills）平台

[![CI](https://github.com/shilaoban666/Honghu-ai-assigmemt/actions/workflows/ci.yml/badge.svg)](https://github.com/shilaoban666/Honghu-ai-assigmemt/actions/workflows/ci.yml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-6DB33F?style=flat-square&logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![Milvus](https://img.shields.io/badge/Milvus-2.4-00A1EA?style=flat-square&logo=milvus&logoColor=white)](https://milvus.io/)
[![Docker](https://img.shields.io/badge/Docker%20Compose-ready-2496ED?style=flat-square&logo=docker&logoColor=white)](#-快速开始docker-一条命令)

**多模型路由 · SSE 流式 · 三层记忆 · 向量 RAG · 技能市场 · Token 计费 · RBAC 权限 · 管理后台**

</div>

<p align="center">
  <img src="docs/screenshots/honghu-ai-chat.png" alt="Honghu AI Chat 界面" width="92%"/>
</p>

---

## 目录

- [项目简介](#项目简介)
- [核心能力](#核心能力)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [快速开始（Docker 一条命令）](#-快速开始docker-一条命令)
- [本地开发启动](#本地开发启动)
- [核心模块详解](#核心模块详解)
- [API 接口](#api-接口)
- [项目结构](#项目结构)
- [测试与 CI](#测试与-ci)
- [路线图](#路线图)

---

## 项目简介

**Honghu AI** 是一个基于 **Spring Boot 3 + Spring AI** 的企业级 AI 应用后端。它不是一个“调用一下大模型 API”的玩具，而是把一套真实可用的 LLM 应用平台所需的工程能力完整落地：

- **统一模型网关**：本地 Ollama 与多家 OpenAI-兼容云厂商（DeepSeek / 通义千问 / Gemini / OpenAI…）统一接入，按任务复杂度自动路由，本地不可用时云端兜底。
- **检索增强（RAG）**：S3 上传 → SQS 事件驱动 → 文档解析（PDF / DOCX / Markdown / 图片 OCR）→ 分块 → Milvus 向量化 → 关键词/向量混合检索 + 作用域兜底。
- **可扩展技能系统**：统一抽象 **MCP / Tool-Calling / CLI / Claude Skills** 四类能力来源，配套能力市场、会话级开关、工具调用审计、SSRF 防护、CLI 危险命令门与密钥加密。
- **计费与配额**：Token + 金额双计价、按用户/工作空间的日月配额、全链路用量审计流水。
- **权限与后台**：Guest / User / VIP / Admin 四级 RBAC，精确到模型级别的访问控制，以及完整的管理后台 API。

> 配套前端为独立的 Vue 3 单页应用（聊天、技能广场、设置中心、计费看板），上方截图即为其运行界面。

---

## 核心能力

<table>
<tr>
<td width="50%">

### 🧠 多模型智能路由
统一网关 `AiChatModelGatewayService` 屏蔽 provider 差异：本地 **Ollama** 走 Spring AI，云端走 **OpenAI-兼容 HTTP 客户端**。按消息复杂度路由轻量/重量模型，本地不可用时**自动回退云端**，并在调用前后做配额校验与成本核算。

</td>
<td width="50%">

### 💬 流式对话 & 三层记忆
**SSE** 逐字流式输出，全量对话持久化至 PostgreSQL。记忆分三层：Redis **Token 滑动窗口**（一层）、超长对话**摘要压缩**（二层）、跨会话**用户画像聚合**（二层），用 JTokkit 精确计 token。

</td>
</tr>
<tr>
<td width="50%">

### 📚 向量 RAG 知识库
事件驱动摄取管线：**S3 → SQS → 解析 → 清洗 → 分块 → 向量化 → Milvus**。检索侧支持关键词/向量两种模式、查询改写、多路融合（RRF）、**作用域兜底**（附件 → 当前对话 → 整个会话）与重排预留。

</td>
<td width="50%">

### 🧩 可扩展技能系统
一套抽象统一 **MCP / Tool-Calling / CLI / Claude Skills**。能力市场数据由定时爬虫从 MCP Registry 与 GitHub 抓取（真实数据，非 mock）。运行期具备工具审计、SSRF 防护、CLI 危险命令门、密钥 AES 加密。

</td>
</tr>
<tr>
<td width="50%">

### 💰 Token 计费与配额
以 **Token 与金额**为统一计费符号，价格快照 + 用量事件（`ai_usage_event`）全链路落账。无论成功、失败、超限还是缺价格都尽量写审计流水，支持个人与工作空间两级配额。

</td>
<td width="50%">

### 🔐 RBAC 权限 & 管理后台
**Guest → User → VIP → Admin** 四级角色，精确到模型级别的访问授权。独立的管理后台可动态增删改 **模型目录** 与 **Provider 注册表**（API Key 经 AES-GCM 加密存储、查询只回掩码），配额 / 计费 / 监控一应俱全，与前台用户体系隔离鉴权。

</td>
</tr>
</table>

---

## 系统架构

<p align="center">
  <img src="docs/images/honghu-ai-system-architecture.svg" alt="Honghu AI 后端系统架构图" width="96%"/>
</p>

> 子系统详细架构图（已收录于 `docs/`，GitHub 可直接预览）：

| 主题 | 图 |
|:---|:---|
| 企业级 RAG 向量检索（DDD 分层） | [`docs/enterprise-rag-ddd-architecture.svg`](docs/enterprise-rag-ddd-architecture.svg) |
| RAG 摄取管线流程 | [`docs/rag_pipeline_flow.png`](docs/rag_pipeline_flow.png) |
| Milvus 生产生命周期 | [`docs/milvus-production-lifecycle.svg`](docs/milvus-production-lifecycle.svg) |
| Spring AI ETL 流 | [`docs/spring-ai-etl-flow.svg`](docs/spring-ai-etl-flow.svg) |
| 技能系统后端架构 | [`docs/skill-mcp-backend-architecture.svg`](docs/skill-mcp-backend-architecture.svg) |
| 技能系统运行时流 | [`docs/skill-mcp-runtime-flow.svg`](docs/skill-mcp-runtime-flow.svg) |
| 技能系统数据模型 | [`docs/skill-mcp-data-model.svg`](docs/skill-mcp-data-model.svg) |

---

## 技术栈

| 分类 | 选型 |
|:---|:---|
| **框架** | Spring Boot 3.2 · Spring AI 1.0.0 · Spring Data JPA · Spring AOP |
| **AI / 向量** | Spring AI（Ollama / OpenAI-兼容 / Milvus VectorStore）· JTokkit（Token 计数） |
| **数据存储** | PostgreSQL 16 · Redis 7 · Milvus 2.4 · Liquibase（YAML-first 迁移） |
| **云 / 消息** | AWS S3（对象存储 + 预签名 URL）· SQS（`@SqsListener` 事件驱动）· Spring Cloud AWS |
| **文档解析** | Apache PDFBox · Apache POI（DOCX）· 图片 OCR |
| **接口 / 工具** | SpringDoc OpenAPI（Swagger UI）· OkHttp · Lombok · Spring Security Crypto |
| **工程化** | Docker / Docker Compose · GitHub Actions CI · JUnit 5 + Mockito（159 单测） |

---

## 🚀 快速开始（Docker 一条命令）

完整本地栈（PostgreSQL + Redis + Milvus 集群 + 应用）一键拉起，应用会等待所有依赖就绪后再启动。

```bash
# 1. 克隆
git clone https://github.com/shilaoban666/Honghu-ai-assigmemt.git
cd Honghu-ai-assigmemt

# 2. 配置至少一个 AI provider 的 Key（启用对话）
cp .env.example .env
#   编辑 .env，填入 DEEPSEEK_CLOUD_API_KEY=sk-xxxx（或 OPENAI_API_KEY / ALIYUN_API_KEY）

# 3. 一条命令拉起整个平台
docker compose up -d --build
```

启动后访问：

| 服务 | 地址 |
|:---|:---|
| 💚 健康检查 | http://localhost:8080/actuator/health |
| 🌐 Swagger UI | http://localhost:8080/swagger-ui.html |
| 📄 OpenAPI | http://localhost:8080/api-docs |
| 🔵 Milvus 指标 | http://localhost:9091/healthz |

> **说明**：S3 上传与 SQS 驱动的 RAG 摄取依赖真实 AWS（或 LocalStack），默认关闭以便一键启动；核心对话、记忆、关键词 RAG 检索、技能系统、管理后台均可直接运行。若需启用本地 Ollama，请在宿主机运行 `ollama serve` 并保留 `.env` 中的 `OLLAMA_LOCAL_BASE_URL`。

---

## 本地开发启动

不使用 Docker 时，需自备 PostgreSQL / Redis / Milvus（可只用 Docker 起依赖，应用本地 `mvn` 运行）：

```bash
# 仅用容器起依赖
docker compose up -d postgres redis milvus

# 本地运行应用（默认连 localhost 依赖；连接参数见 application.yml 环境变量）
./mvnw spring-boot:run
```

机器相关的密钥放在 `src/main/resources/application-local.yml`（已 `.gitignore`），不会进入仓库。

---

## 核心模块详解

<details>
<summary><b>🧠 多模型网关 & 智能路由</b></summary>

- `AiChatModelGatewayService`：所有聊天调用的统一入口。按 provider 类型分流到本地 Ollama 或 OpenAI-兼容 HTTP 客户端；本地不可用时按配置回退云端；调用前查配额、调用后按 token 用量与价格快照算成本，并写 `ai_usage_event` 审计流水。
- `AiTaskKeywordService`：基于关键词的任务分类（CODE / DESIGN / ANALYSIS / DATA_PROCESSING / TEXT），决定路由到轻量还是重量模型。
- `AiModelAccessService`：角色 + 个性化授权 → 实际可用模型列表。

</details>

<details>
<summary><b>📚 RAG 摄取与检索管线</b></summary>

**摄取**：S3 上传完成 → SQS 事件（`RagDocumentUploadedListener`）→ 拉取文件 → 按类型解析（PDF / DOCX / Markdown / 纯文本 / 图片 OCR）→ 文本清洗 → 多策略分块（递归结构 / 段落打包 / 句子窗口 / 重叠窗口）→ Embedding → 写入 Milvus（自定义 `MilvusVectorRepository` 做幂等 delete/insert）。

**检索**：`ScopeResolver`（解析作用域）→ `QueryAnalyzer`（可选改写）→ 关键词/向量召回 → `ResultFusion`（RRF 融合）→ `RagScopeFallback`（命中不足时 FILE_IDS → CHAT → SESSION 逐级扩大作用域）→ 排序 / 截断 / 格式化为 Prompt 上下文。

</details>

<details>
<summary><b>🧩 技能 / 能力系统</b></summary>

- 统一抽象四类能力来源：**MCP Server**、原生 **Tool-Calling**、**CLI** 命令、**Claude Skills**。
- `CapabilityService` 提供能力的安装 / 启用 / 分页浏览；能力市场数据由 `McpRegistryCrawler` / `ClaudeSkillCrawler` / `PulseMcpChineseCrawler` / `ChinesePromptSkillCrawler` 定时从 MCP Registry 与 GitHub 抓取真实数据。
- 安全：`ToolGuard`（工具访问决策）、`SkillAccessPolicy`、MCP 端点 **SSRF 防护**、CLI **危险命令门**（`SandboxedCommandRunner`）、`SecretCipher`（凭证加密）、`AuditingToolCallback`（调用审计）。

</details>

<details>
<summary><b>💰 计费 / 配额 / 用量</b></summary>

- Token 与金额双计价；`BillingService` 按模型价格快照核算成本；`QuotaService` 支持个人与工作空间的日/月配额；`UsageEventService` 落全链路用量审计（成功 / 失败 / 超限 / 缺价格）。

</details>

---

## API 接口

完整接口见 **Swagger UI**（`/swagger-ui.html`）。主要分组：

| 分组 | 前缀 | 说明 |
|:---|:---|:---|
| 💬 聊天 | `/api/v1/chat` | 结构化 / 流式(SSE) / 持久化流式 / 历史查询 |
| 📂 会话 | `/api/v1/sessions` | 会话列表 / 详情 / 重命名 / 删除 |
| 👤 用户 | `/api/v1/users` | 注册 / 登录 / 资料 / 头像 / 配额查询 |
| 📚 RAG | `/api/v1/rag` | 文档上传(预签名) / 摄取状态(SSE) / 检索 |
| 🧩 能力 | `/api/v1/capabilities` · `/api/v1/skills` | 能力市场 / 安装 / 会话级开关 |
| 🛡️ 管理 | `/api/v1/admin` | 用户 / 模型 / 配额 / 计费 / 后台鉴权 |
| 📊 监控 | `/api/monitor` | CPU / 内存 / 线程指标总览 |

<details>
<summary>示例：结构化流式聊天（SSE）</summary>

```http
POST /api/v1/chat/structured/stream/persistent
Content-Type: application/json

{
  "message": "用 Java 实现一个线程安全的单例并讲解原理",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```
> 返回 Server-Sent Events，逐字输出；对话自动持久化并接入三层记忆。
</details>

---

## 项目结构

```
honghu-ai/
├── src/main/java/com/honghu/ai/assigment/
│   ├── controller/      # REST 控制器（Chat / Rag / User / Monitor …）
│   ├── service/         # 业务服务（32 个：网关 / 记忆 / 摘要 / 计费 …）
│   ├── rag/             # RAG 摄取 + 检索（解析 / 清洗 / 分块 / 向量 / 融合 / 兜底）
│   ├── skill/           # 技能系统（core / crawler / builtin / security / dto）
│   ├── admin/           # 管理后台（控制器 + 鉴权服务）
│   ├── memory/          # 记忆摘要客户端
│   ├── manager/         # AWS 资源统一封装
│   ├── security/        # 用户会话 Token
│   ├── entity/ repository/ dto/ config/ exception/ monitor/ listener/ util/
│   └── HonghuAiApplication（@SpringBootApplication 扫描入口）
├── src/main/resources/
│   ├── application.yml           # 全量配置（环境变量占位，无明文密钥）
│   ├── prompts/                  # 系统/任务/摘要提示词库
│   └── DB/changelog/*.yaml        # Liquibase（YAML-first）
├── src/test/                      # 159 单元测试（Mockito）
├── docs/                          # 架构图（SVG / PNG）+ 设计文档
├── Dockerfile · docker-compose.yml · .env.example
└── .github/workflows/ci.yml       # GitHub Actions CI
```

> Liquibase 采用 YAML-first：主入口 `db.changelog-master.yaml`，各子 changelog 保留原 `.xml` `logicalFilePath` 以兼容历史 `DATABASECHANGELOG` 校验和。

---

## 测试与 CI

```bash
# 全部测试（含需要基础设施的 @SpringBootTest，需本地起依赖）
./mvnw test

# 仅跑无依赖的单元测试集（159 个，CI 同款）
./mvnw -Pci test
```

- **GitHub Actions**（`.github/workflows/ci.yml`）：每次 push / PR 到 `master`、`dev` 自动用 JDK 17 构建并跑 `mvn -Pci clean verify`，产物上传为 artifact。
- `ci` profile 排除依赖 PostgreSQL / Redis / Milvus / 实时模型端点的上下文测试与手动运行器，保证 CI 在无基础设施环境下稳定通过。

---

## 路线图

- [ ] 引入 Spring Security + JWT 过滤链，替换当前轻量会话 Token（接口已通过 `CurrentUserService` 解耦，替换不影响业务层）
- [ ] 技能 CLI 执行接入操作系统级沙箱
- [ ] 工具调用审计异步化
- [ ] RAG 重排（rerank）接入真实模型，向量检索默认化

---

<div align="center">

**🦅 Honghu AI** — 把一套真实可用的 LLM 应用平台，完整地工程化落地。

</div>
