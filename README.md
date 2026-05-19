<div align="center">

<img src="https://img.shields.io/badge/🌿-Honghu_AI-2e7d32?style=for-the-badge&labelColor=43a047&logo=data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjQiIGhlaWdodD0iMjQiIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0id2hpdGUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+PHBhdGggZD0iTTEyIDJDNi40OCAyIDIgNi40OCAyIDEyczQuNDggMTAgMTAgMTAgMTAtNC40OCAxMC0xMFMxNy41MiAyIDEyIDJ6Ii8+PC9zdmc+" alt="Honghu AI"/>

# 🌲 Honghu AI

### 🤖 企业级多模型智能对话平台

<br/>

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0--M3-6DB33F?style=flat-square&logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7.x-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![DeepSeek](https://img.shields.io/badge/DeepSeek-R1-4A90D9?style=flat-square&logo=openai&logoColor=white)](https://www.deepseek.com/)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

<br/>

**智能对话助手，为您服务** · 多模型路由 · 会话记忆 · 权限管控 · 流式响应

<br/>

[🚀 快速开始](#-快速开始) · [📖 API 文档](#-api-接口文档) · [🏗️ 架构设计](#️-系统架构) · [⚙️ 配置指南](#️-配置说明)

---

</div>

<br/>

## ✨ 功能亮点

<table>
<tr>
<td width="50%">

### 🧠 智能模型路由
根据用户消息**自动分析任务复杂度**，智能选择最适合的 AI 模型。简单问答走轻量 8B，复杂编程/分析走 32B，**成本与效果的最佳平衡**。

</td>
<td width="50%">

### 💬 流式对话 & 持久化
支持 **SSE 实时流式响应**，打字机效果逐字输出。全量对话自动持久化至 PostgreSQL，**随时回溯历史上下文**。

</td>
</tr>
<tr>
<td width="50%">

### 🔐 角色权限体系
四级用户角色 (Guest → User → VIP → Admin)，**精细到模型级别的访问控制**。支持个性化授权，灵活管理 AI 资源分配。

</td>
<td width="50%">

### 📝 记忆压缩引擎
基于 Token 计数的**滑动窗口记忆**，搭配 Redis 高速缓存。对话过长时自动触发**智能摘要压缩**，保留关键上下文不丢失。

</td>
</tr>
</table>

<br/>

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          🌐 Client Layer                            │
│                  Swagger UI / Web App / API Client                   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  HTTP / SSE
┌──────────────────────────────▼──────────────────────────────────────┐
│                       🎯 Controller Layer                           │
│  ┌──────────────┐ ┌────────────────┐ ┌────────────┐ ┌───────────┐  │
│  │ChatController│ │ UserController │ │SessionCtrl │ │MonitorCtrl│  │
│  │  /api/v1/chat│ │ /api/v1/users  │ │/api/v1/sess│ │/api/monitor│ │
│  └──────┬───────┘ └───────┬────────┘ └─────┬──────┘ └─────┬─────┘  │
└─────────┼─────────────────┼────────────────┼───────────────┼────────┘
          │                 │                │               │
┌─────────▼─────────────────▼────────────────▼───────────────▼────────┐
│                        ⚙️ Service Layer                             │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    ChatService (核心)                        │    │
│  │  结构化聊天 · 流式聊天 · 持久化聊天 · 任务分类路由           │    │
│  └──────────┬────────────────────────────────┬─────────────────┘    │
│             │                                │                       │
│  ┌──────────▼──────────┐    ┌────────────────▼─────────────────┐    │
│  │AiTaskKeywordService │    │    AiChatModelGatewayService     │    │
│  │  关键词匹配          │    │     统一模型调用网关              │    │
│  │  5种任务分类         │    │  ┌─────────────┬──────────────┐  │    │
│  └─────────────────────┘    │  │ Ollama本地   │ OpenAI兼容   │  │    │
│                              │  │ DeepSeek R1  │ 外部API      │  │    │
│  ┌─────────────────────┐    │  └─────────────┴──────────────┘  │    │
│  │ ChatMemoryService   │    └──────────────────────────────────┘    │
│  │  滑动窗口记忆        │                                           │
│  │  Token计数(JTokkit)  │    ┌──────────────────────────────────┐    │
│  └─────────────────────┘    │   AiModelAccessService           │    │
│                              │   角色+权限 → 可用模型列表        │    │
│  ┌─────────────────────┐    └──────────────────────────────────┘    │
│  │ChatSummaryService   │                                            │
│  │  会话摘要压缩        │                                            │
│  └─────────────────────┘                                            │
└─────────────────────┬───────────────────────────────┬───────────────┘
                      │                               │
┌─────────────────────▼───────┐   ┌───────────────────▼───────────────┐
│     🐘 PostgreSQL           │   │          🔴 Redis                  │
│  ┌────────┐ ┌────────────┐  │   │                                    │
│  │ users  │ │chat_session│  │   │   记忆窗口缓存 · Token 计数缓存    │
│  ├────────┤ ├────────────┤  │   │                                    │
│  │chat_msg│ │ai_model_def│  │   └────────────────────────────────────┘
│  ├────────┤ ├────────────┤  │
│  │task_kw │ │user_model_ │  │   ┌────────────────────────────────────┐
│  │        │ │ permission │  │   │        🦙 Ollama (本地)             │
│  └────────┘ └────────────┘  │   │   DeepSeek R1:8B / R1:32B          │
│  Liquibase 版本管理          │   └────────────────────────────────────┘
└─────────────────────────────┘
```

<br/>

## 🤖 AI 模型 & 智能路由

### 任务分类引擎

系统根据消息内容自动识别任务类型，匹配最佳模型：

| 任务类型 | 关键词示例 | 路由模型 | 说明 |
|:---:|:---|:---:|:---|
| 🖥️ **CODE** | `代码` `函数` `debug` `重构` | R1:32B | 代码生成、调试、优化 |
| 📐 **DESIGN** | `架构` `方案` `设计模式` | R1:32B | 系统架构、方案设计 |
| 📊 **ANALYSIS** | `分析` `推理` `对比` | R1:32B | 深度分析、逻辑推理 |
| 🔄 **DATA** | `清洗` `转换` `ETL` | R1:32B | 数据处理、格式转换 |
| ✍️ **TEXT** | `翻译` `润色` `改写` | R1:8B/32B | 文本创作、翻译润色 |

> **路由策略**：消息长度 ≥ 20字 **或** 命中复杂任务关键词 → 32B 大模型；否则 → 8B 轻量模型

### 权限模型

```
🏠 GUEST   ──→  本地模型 + 第二梯队          (基础体验)
👤 USER    ──→  全部第二梯队模型              (标准服务)
⭐ VIP     ──→  全部已启用模型                (高级服务)
🛡️ ADMIN   ──→  全部已启用模型 + 管理后台     (完全控制)
```

<br/>

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|:---:|:---:|:---|
| ☕ JDK | 17+ | 推荐 JDK 21 |
| 🐘 PostgreSQL | 14+ | 数据持久化 |
| 🔴 Redis | 6+ | 记忆缓存 |
| 🦙 Ollama | 最新版 | 本地模型推理 |
| 📦 Maven | 3.8+ | 项目构建 |

### 一键启动

```bash
# 1️⃣ 克隆项目
git clone https://github.com/your-org/honghu-ai.git
cd honghu-ai

# 2️⃣ 拉取 DeepSeek R1 模型
ollama pull deepseek-r1:8b
ollama pull deepseek-r1:32b    # 可选，需要更多显存

# 3️⃣ 启动应用
# Windows
start-app.bat

# Linux / macOS
chmod +x start-app.sh && ./start-app.sh

# 或直接使用 Maven
mvn spring-boot:run
```

### 验证服务

```bash
# 健康检查
curl http://localhost:8080/health/ping

# 快速聊天
curl "http://localhost:8080/api/v1/chat/simple?message=你好"
```

启动成功后访问：

| 服务 | 地址 |
|:---|:---|
| 🌐 Swagger UI | http://localhost:8080/swagger-ui.html |
| 📄 API 文档 | http://localhost:8080/api-docs |
| 💚 健康检查 | http://localhost:8080/health |
| 📊 系统监控 | http://localhost:8080/api/monitor/overview |

<br/>

## 📖 API 接口文档

### 💬 聊天接口 `/api/v1/chat`

<details>
<summary><b>GET</b> <code>/simple</code> — 简单聊天</summary>

```http
GET /api/v1/chat/simple?message=你好
```
```json
{
  "content": "你好！我是 Honghu AI 助手，很高兴为你服务。",
  "model": "deepseek-r1:8b",
  "timestamp": 1740280000000,
  "success": true
}
```
</details>

<details>
<summary><b>POST</b> <code>/structured</code> — 结构化聊天（推荐）</summary>

```http
POST /api/v1/chat/structured
Content-Type: application/json
```
```json
{
  "message": "用 Java 实现一个线程安全的单例模式",
  "model": "deepseek-r1:32b",
  "systemMessage": "你是一个资深 Java 架构师",
  "temperature": 0.7,
  "maxTokens": 4096
}
```
</details>

<details>
<summary><b>POST</b> <code>/structured/stream</code> — 流式聊天 (SSE)</summary>

```http
POST /api/v1/chat/structured/stream
Accept: text/event-stream
Content-Type: application/json
```
```json
{
  "message": "讲解 Spring AOP 原理",
  "stream": true
}
```
> 返回 Server-Sent Events 流，逐字输出 AI 响应
</details>

<details>
<summary><b>POST</b> <code>/structured/stream/persistent</code> — 持久化流式聊天</summary>

```http
POST /api/v1/chat/structured/stream/persistent
Content-Type: application/json
```
```json
{
  "message": "帮我设计一个微服务架构",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "user-001"
}
```
> 自动保存对话到数据库 + 集成记忆管理
</details>

<details>
<summary><b>GET</b> <code>/history/{sessionId}</code> — 查询对话历史</summary>

```http
GET /api/v1/chat/history/550e8400-e29b-41d4-a716-446655440000
```
</details>

### 👤 用户接口 `/api/v1/users`

| 方法 | 路径 | 说明 |
|:---:|:---|:---|
| `POST` | `/` | 创建用户 |
| `GET` | `/{userId}` | 查询用户 |
| `GET` | `/` | 用户列表 |
| `PUT` | `/{userId}` | 更新用户 |
| `DELETE` | `/{userId}` | 删除用户 |
| `POST` | `/login` | 用户登录 |
| `GET` | `/search?keyword=xxx` | 搜索用户 |
| `GET` | `/status/{status}` | 按状态过滤 |

### 📂 会话接口 `/api/v1/sessions`

| 方法 | 路径 | 说明 |
|:---:|:---|:---|
| `GET` | `/` | 所有会话 |
| `GET` | `/user/{userId}` | 用户的会话 |
| `GET` | `/{sessionId}` | 查询会话 |
| `DELETE` | `/{sessionId}` | 删除会话 |
| `PUT` | `/{sessionId}/rename` | 重命名会话 |

### 📊 监控接口 `/api/monitor`

| 方法 | 路径 | 说明 |
|:---:|:---|:---|
| `GET` | `/metrics` | CPU / 内存 / 线程指标 |
| `GET` | `/overview` | 监控总览 |
| `GET` | `/refresh` | 刷新指标 |

<br/>

## 📁 项目结构

```
honghu-ai/
│
├── 📂 src/main/java/.../testdeepseekr1/
│   ├── 🎯 controller/                  # REST 控制器
│   │   ├── ChatController.java          #   聊天 API (核心)
│   │   ├── UserController.java          #   用户管理
│   │   ├── ChatSessionController.java   #   会话管理
│   │   ├── HealthController.java        #   健康检查
│   │   └── MonitorController.java       #   系统监控
│   │
│   ├── ⚙️ service/                     # 业务服务层
│   │   ├── ChatService.java             #   聊天核心逻辑
│   │   ├── AiChatModelGatewayService    #   模型调用网关
│   │   ├── AiModelAccessService.java    #   权限与模型访问
│   │   ├── AiTaskKeywordService.java    #   任务分类引擎
│   │   ├── ChatMemoryService.java       #   记忆管理
│   │   └── ChatSummaryService.java      #   会话摘要压缩
│   │
│   ├── 📦 entity/                      # JPA 数据实体
│   │   ├── User.java                    #   用户
│   │   ├── ChatSession.java             #   会话
│   │   ├── ChatMessage.java             #   消息
│   │   ├── AiModelDefinition.java       #   模型定义
│   │   ├── AiTaskKeyword.java           #   任务关键词
│   │   └── UserModelPermission.java     #   用户模型权限
│   │
│   ├── 🗃️ repository/                  # 数据访问层
│   ├── 📋 dto/                         # 数据传输对象
│   ├── ⚠️ exception/                   # 全局异常处理
│   ├── 🔧 config/                      # 配置类
│   └── 📡 monitor/                     # 系统监控
│
├── 📂 src/main/resources/
│   ├── application.yml                  # 应用配置
│   ├── 📂 prompts/                     # AI 提示词库
│   │   ├── default-system-prompt.txt    #   默认系统提示
│   │   ├── 📂 tasks/                   #   任务专属提示词
│   │   └── 📂 summary/                 #   摘要压缩提示词
│   └── 📂 DB/changelog/               # Liquibase 数据库变更
│
├── 📂 src/test/                        # 单元测试
├── start-app.bat / .sh                  # 启动脚本
├── pom.xml                              # Maven 配置
└── README.md                            # 📖 你在这里
```

<br/>

## ⚙️ 配置说明

### 核心配置 `application.yml`

```yaml
# 🤖 AI 模型配置
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: deepseek-r1:8b
          temperature: 0.7

# 🎯 智能路由
app:
  ai:
    default-model: deepseek-r1:8b
    routing:
      simpleDefaultModel: deepseek-r1:8b     # 简单任务 → 8B
      complexDefaultModel: deepseek-r1:32b   # 复杂任务 → 32B
  chat:
    memory:
      max-tokens: 8000                       # 记忆窗口大小
```

### 超时配置

| 参数 | 值 | 说明 |
|:---|:---:|:---|
| 连接超时 | 60s | 建立连接最大等待 |
| 读取超时 | 300s | AI 思考时间，需要充足 |
| 写入超时 | 60s | 请求发送超时 |

<br/>

## 🧠 记忆系统原理

```
📨 用户消息
    │
    ▼
┌────────────────────────────┐
│  ChatMemoryService         │
│  ┌──────────────────────┐  │
│  │ 1. 查询 Redis 缓存   │  │──→ 命中 → 直接返回记忆窗口
│  └──────────┬───────────┘  │
│             │ 未命中        │
│  ┌──────────▼───────────┐  │
│  │ 2. 从 DB 加载历史     │  │
│  └──────────┬───────────┘  │
│             │               │
│  ┌──────────▼───────────┐  │
│  │ 3. JTokkit Token计数  │  │
│  │    CL100K_BASE 编码   │  │
│  └──────────┬───────────┘  │
│             │               │
│  ┌──────────▼───────────┐  │
│  │ 4. 超限? → 摘要压缩   │  │──→ ChatSummaryService
│  └──────────┬───────────┘  │
│             │               │
│  ┌──────────▼───────────┐  │
│  │ 5. 写入 Redis 缓存    │  │
│  └──────────────────────┘  │
└────────────────────────────┘
    │
    ▼
🤖 携带上下文发送至 AI 模型
```

<br/>

## 🗄️ 数据库设计

```
┌──────────┐       ┌──────────────┐       ┌──────────────┐
│  users   │───1:N─│ chat_session │───1:N─│ chat_message │
│──────────│       │──────────────│       │──────────────│
│ user_id  │       │ session_id   │       │ chat_id      │
│ username │       │ user_id (FK) │       │ session_id   │
│ email    │       │ title        │       │ chat_role    │
│ phone    │       │ status       │       │ content      │
│ userRole │       │ created_at   │       │ created_at   │
└────┬─────┘       └──────────────┘       └──────────────┘
     │
     │1:N     ┌───────────────────────┐
     └────────│ user_model_permission │
              │───────────────────────│
              │ user_id (FK)          │
              │ model_code (FK)       │     ┌─────────────────────┐
              │ enabled               │─────│ ai_model_definition │
              └───────────────────────┘     │─────────────────────│
                                            │ model_code          │
              ┌───────────────────┐         │ display_name        │
              │ ai_task_keyword   │         │ provider_code       │
              │───────────────────│         │ api_model_name      │
              │ keyword           │         │ level               │
              │ task_type         │         │ local_model         │
              │ enabled           │         └─────────────────────┘
              └───────────────────┘
```

> 使用 **Liquibase** 进行数据库版本管理，变更记录位于 `src/main/resources/DB/changelog/`
>
> 当前项目采用 **YAML-first** 维护方式：
> - Spring Boot 运行入口使用 `db.changelog-master.yaml`
> - 各子 changelog 也统一使用 `.yaml`
> - 为了兼容历史 `DATABASECHANGELOG` 记录，各 YAML 文件仍保留原 `.xml` `logicalFilePath`
> - 因此数据库里看到的 `filename` 仍可能是 `db.changelog-*.xml`，这是兼容设计，不代表运行时还在加载 XML 文件

### Liquibase YAML-first 约定

- 运行时主入口：`src/main/resources/DB/changelog/db.changelog-master.yaml`
- 历史兼容锚点：各 YAML 文件首行的 `logicalFilePath: "DB/changelog/*.xml"`
- 维护原则：
  - 新增/修改变更请优先编辑 `.yaml`
  - 不要随意修改已有 `changeSet id + author + logicalFilePath`
  - 如果要排查校验和，请优先依据 `DATABASECHANGELOG.filename` 中保留的 `.xml` 逻辑路径来定位

<br/>

## 🛠️ 故障排除

<details>
<summary><b>❌ Connection refused — 连接被拒绝</b></summary>

- 确认 Ollama 服务已启动：`ollama serve`
- 若使用远程 GPU，检查 SSH 隧道：`ssh -L 11434:127.0.0.1:11434 -p 23 root@<your-host>`
- 运行 `test_connection.bat` 自动检测连通性
</details>

<details>
<summary><b>⏱️ SocketTimeoutException — 响应超时</b></summary>

- 32B 模型首次推理较慢，耐心等待
- 检查 GPU 显存是否充足
- 适当增加读取超时配置
</details>

<details>
<summary><b>🔍 模型未找到</b></summary>

```bash
# 查看已安装模型
ollama list

# 拉取缺失模型
ollama pull deepseek-r1:8b
```
</details>

<details>
<summary><b>🐘 数据库连接失败</b></summary>

- 确认 PostgreSQL 服务已启动
- 检查 `application.yml` 中的数据库连接配置
- 确保 Liquibase 变更已正确执行
</details>

<br/>

## 🧪 测试

```bash
# 运行全部单元测试
mvn test

# 运行指定测试类
mvn test -Dtest=ChatServiceRoutingTest

# Windows 一键测试
run_tests.bat
```

包含以下测试套件：

| 测试类 | 覆盖范围 |
|:---|:---|
| `AiModelAccessServiceTest` | 模型访问权限验证 |
| `AiTaskKeywordServiceTest` | 任务关键词分类 |
| `ChatMemoryServiceTest` | 记忆窗口管理 |
| `ChatServiceRoutingTest` | 智能路由策略 |
| `ChatSummaryServiceTest` | 会话摘要压缩 |
| `ConnectionExceptionTest` | 异常连接处理 |

<br/>

## 📚 相关文档

| 文档 | 说明 |
|:---|:---|
| [CONFIGURATION.md](CONFIGURATION.md) | 完整配置参数说明 |
| [HELP.md](HELP.md) | 帮助与常见问题 |
| [DeployLog/](DeployLog/) | 部署日志记录 |

<br/>

---

<div align="center">

**🌿 Honghu AI** — 让每一次对话都有价值

<small>© 2026 Honghu AI. All rights reserved.</small>

<br/>

[![Made with ❤️](https://img.shields.io/badge/Made%20with-❤️-red?style=flat-square)](https://github.com/your-org/honghu-ai)
[![Spring Boot](https://img.shields.io/badge/Powered%20by-Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io)
[![DeepSeek](https://img.shields.io/badge/AI%20by-DeepSeek%20R1-4A90D9?style=flat-square)](https://www.deepseek.com)

</div>
