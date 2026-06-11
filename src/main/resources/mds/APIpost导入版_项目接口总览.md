# testDeepseekR1 API 文档（APIpost 导入版）

> 文档用途：基于当前项目代码整理的接口总览，便于直接导入 APIpost 或发给前端联调。
>
> 文档范围：**以当前 Controller / DTO / 全局异常处理器的实际代码行为为准**。

---

# 1. 基础信息

- **项目名称**：`honghu AI Chat`
- **默认地址**：`http://localhost:8080`
- **Context Path**：`/`
- **Swagger 地址**：`http://localhost:8080/swagger-ui.html`
- **OpenAPI 地址**：`http://localhost:8080/api-docs`

---

# 2. 通用约定

## 2.1 通用请求头

| Header | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `Content-Type` | string | 大多数 POST/PUT/PATCH 是 | 固定为 `application/json` |
| `X-User-Id` | string | 聊天相关接口建议传；持久化流式接口实际上必传 | 用户 ID，请求头优先级高于请求体中的 `userId` |

## 2.2 用户身份与模型权限规则

登录后返回的 `identity` / `identityLabel` / `permissionSummary` / `availableModels` 用于前端做权限展示与模型下拉框渲染。

| identity | 中文名 | 权限说明 |
| --- | --- | --- |
| `GUEST` | 游客 | 只能使用普通模型，当前默认是 **本地 + 第二梯队模型** |
| `USER` | 普通用户 | 只能使用 **第二梯队模型** |
| `VIP` | VIP用户 | 可使用 **全部梯队模型** |
| `ADMIN` | admin用户 | 可使用 **全部梯队模型** |

> 说明：
> - 模型梯队由模型定义里的 `level` 控制。
> - `level=1`：第一梯队。
> - `level=2`：第二梯队。
> - 前端后续调用聊天接口时，建议只允许从 `availableModels[].modelCode` 中选值。

## 2.3 错误返回格式

当前项目大部分异常最终会返回 `ChatResponse` 结构：

```json
{
  "content": null,
  "model": null,
  "timestamp": 1744171200000,
  "success": false,
  "errorMessage": "系统内部错误: 用户不存在：zhangsan",
  "tokenUsage": null
}
```

---

# 3. 登录与用户接口

## 3.1 POST /api/v1/users/login

### 接口说明

用户登录接口，支持：

1. 普通用户名密码登录
2. 游客登录

登录成功后直接返回增强版 `UserResponse`，包含：

- 用户基础信息
- `identity`
- `identityLabel`
- `permissionSummary`
- `availableModels`

### 请求头

| Header | 必填 | 示例 |
| --- | --- | --- |
| `Content-Type` | 是 | `application/json` |

### 请求体

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `guestLogin` | boolean | 否 | 是否游客登录；为 `true` 时可不传用户名密码 |
| `username` | string | 条件必填 | 普通登录时传 |
| `password` | string | 条件必填 | 普通登录时传 |

### 普通登录示例

```json
{
  "guestLogin": false,
  "username": "zhangsan",
  "password": "123456"
}
```

### 游客登录示例

```json
{
  "guestLogin": true
}
```

### 成功响应示例

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "zhangsan",
  "nickname": "张三",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "gender": "MALE",
  "userStatus": "ACTIVE",
  "userRole": "USER",
  "identity": "USER",
  "identityLabel": "普通用户",
  "permissionSummary": "普通用户只能使用第二梯队模型",
  "availableModels": [
    {
      "modelCode": "deepseek-r1:8b",
      "displayName": "DeepSeek R1 8B Local",
      "providerCode": "ollama-local",
      "apiModelName": "deepseek-r1:8b",
      "level": 2,
      "localModel": true,
      "supportsStream": true,
      "description": "本地 Ollama 8B 模型"
    }
  ],
  "homeAddress": "北京市朝阳区 XX 街道 XX 号",
  "createdAt": "2026-03-15T10:00:00",
  "updatedAt": "2026-04-01T09:30:00"
}
```

### 响应字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | string | 用户 ID |
| `username` | string | 用户名 |
| `nickname` | string | 昵称 |
| `phone` | string | 手机号 |
| `email` | string | 邮箱 |
| `gender` | string | `MALE` / `FEMALE` / `OTHER` |
| `userStatus` | string | `ACTIVE` / `INACTIVE` / `BANNED` |
| `userRole` | string | 数据库中的用户角色 |
| `identity` | string | 登录后的权限身份 |
| `identityLabel` | string | 身份中文名称 |
| `permissionSummary` | string | 权限摘要 |
| `availableModels` | array | 当前用户可用模型列表 |
| `homeAddress` | string | 家庭地址 |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 更新时间 |

---

## 3.2 POST /api/v1/users

### 接口说明

创建用户。

### 请求体

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名，唯一 |
| `password` | string | 是 | 密码 |
| `nickname` | string | 否 | 昵称 |
| `phone` | string | 否 | 手机号，唯一 |
| `email` | string | 否 | 邮箱，唯一 |
| `gender` | string | 否 | `MALE` / `FEMALE` / `OTHER` |
| `userStatus` | string | 否 | `ACTIVE` / `INACTIVE` / `BANNED` |
| `userRole` | string | 否 | `GUEST` / `USER` / `VIP` / `ADMIN` |
| `homeAddress` | string | 否 | 家庭地址 |

### 请求示例

```json
{
  "username": "lisi",
  "password": "123456",
  "nickname": "李四",
  "email": "lisi@example.com",
  "userRole": "USER"
}
```

### 成功响应示例

```json
{
  "userId": "0ef7ef95-4d80-4ec4-b95d-9ee7b36782dd",
  "username": "lisi",
  "nickname": "李四",
  "phone": null,
  "email": "lisi@example.com",
  "password": "123456",
  "gender": "OTHER",
  "userStatus": "ACTIVE",
  "userRole": "USER",
  "homeAddress": null,
  "createdAt": "2026-04-09T10:00:00",
  "updatedAt": "2026-04-09T10:00:00"
}
```

> 注意：当前 `createUser` 直接返回的是 `User` 实体，不是 `UserResponse`。

---

## 3.3 GET /api/v1/users/{userId}

### 接口说明

根据用户 ID 查询用户详情。

### 路径参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | string | 是 | 用户 ID |

### 成功响应

返回 `User` 实体结构。

---

## 3.4 GET /api/v1/users

### 接口说明

获取所有用户。

### 成功响应

返回 `User[]`。

---

## 3.5 PUT /api/v1/users/{userId}

### 接口说明

全量更新用户。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `userId` | string | 是 |

### 请求体

使用 `User` 实体结构，建议传完整字段。

### 成功响应

返回更新后的 `User`。

---

## 3.6 PATCH /api/v1/users/{userId}

### 接口说明

部分更新用户，仅更新请求体中非空字段。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `userId` | string | 是 |

### 请求体示例

```json
{
  "nickname": "新的昵称",
  "email": "new@example.com"
}
```

### 成功响应

返回更新后的 `User`。

---

## 3.7 DELETE /api/v1/users/{userId}

### 接口说明

删除指定用户。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `userId` | string | 是 |

### 成功响应

- HTTP 状态码：`204 No Content`
- 无响应体

---

## 3.8 GET /api/v1/users/status/{status}

### 接口说明

按用户状态查询用户列表。

### 路径参数

| 参数 | 类型 | 必填 | 可选值 |
| --- | --- | --- | --- |
| `status` | string | 是 | `ACTIVE` / `INACTIVE` / `BANNED` |

### 成功响应

返回 `User[]`。

---

## 3.9 GET /api/v1/users/search?nickname=xxx

### 接口说明

按昵称模糊查询用户。

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `nickname` | string | 是 | 昵称关键词 |

### 成功响应

返回 `User[]`。

---

# 4. 聊天接口

## 4.1 GET /api/v1/chat/simple

### 接口说明

简单非流式聊天。

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | string | 是 | 用户输入内容 |

### 请求示例

```http
GET /api/v1/chat/simple?message=你好
```

### 成功响应示例

```json
{
  "content": "你好！有什么可以帮你？",
  "model": "deepseek-r1:8b",
  "timestamp": 1744171200000,
  "success": true,
  "errorMessage": null,
  "tokenUsage": {
    "promptTokens": 12,
    "completionTokens": 20,
    "totalTokens": 32
  }
}
```

---

## 4.2 POST /api/v1/chat/structured

### 接口说明

结构化非流式聊天。

### 请求头

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Content-Type` | 是 | `application/json` |
| `X-User-Id` | 否 | 若传，则覆盖请求体中的 `userId` |

### 请求体

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | string | 是 | - | 用户消息 |
| `sessionId` | string | 否 | - | 会话 ID，非流式结构化接口中不依赖该值完成存储 |
| `userId` | string | 否 | - | 用户 ID |
| `model` | string | 否 | 自动路由/默认模型 | 模型编码 |
| `stream` | boolean | 是 | `false` | 是否流式，当前 DTO 要求非空 |
| `systemMessage` | string | 否 | 从配置文件提示词读取 | 系统提示词 |
| `temperature` | number | 否 | `0.7` | 温度 |
| `maxTokens` | integer | 否 | `2048` | 最大输出 token |
| `tools` | array | 否 | - | 工具列表 |

### 请求示例

```json
{
  "message": "请帮我总结一下这段方案",
  "model": "deepseek-r1:8b",
  "stream": false,
  "temperature": 0.7,
  "maxTokens": 1024
}
```

### 成功响应

返回 `ChatResponse`。

---

## 4.3 GET /api/v1/chat/stream

### 接口说明

简单流式聊天，返回文本流。

### Query 参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `message` | string | 是 |

### 响应类型

`text/event-stream`

### 响应示例

```text
data: 你

data: 好

data: ，

data: 有什么可以帮你？
```

---

## 4.4 POST /api/v1/chat/structured/stream

### 接口说明

结构化流式聊天，响应为 SSE。

> **重要说明：当前代码中该接口与 `/api/v1/chat/structured/stream/persistent` 实际共用同一套服务实现。**
>
> 也就是说，它当前同样会进入“会话持久化 + 历史记忆”链路。

### 请求头

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Content-Type` | 是 | `application/json` |
| `X-User-Id` | 强烈建议传 | 若不传，当前实现可能无法完成会话型流式处理 |

### 请求体

与 `POST /api/v1/chat/structured/stream/persistent` 相同。

### 响应类型

`text/event-stream`

### 响应块结构

每个 SSE 数据块都会是一个 `ChatResponse` JSON：

```json
{
  "content": "你",
  "model": "deepseek-r1:8b",
  "timestamp": 1744171200000,
  "success": true,
  "errorMessage": null,
  "tokenUsage": null
}
```

---

## 4.5 POST /api/v1/chat/structured/stream/persistent

### 接口说明

持久化结构化流式聊天接口。

当前能力包括：

1. 会话自动创建或复用
2. 用户消息与 AI 回复落库
3. Redis 短期记忆
4. Token 限制下的历史消息截取
5. 会话摘要 / 用户画像后台刷新

### 请求头

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Content-Type` | 是 | `application/json` |
| `X-User-Id` | 是（实际） | 当前服务实现会根据用户 ID 查用户，不传会报错 |

### 请求体

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | string | 是 | - | 用户消息 |
| `sessionId` | string | 否 | 后端自动生成 | 会话 ID；建议前端首次也自行生成并传入，便于后续续聊 |
| `userId` | string | 否 | - | 用户 ID，会被请求头覆盖 |
| `model` | string | 否 | 自动路由 | 模型编码 |
| `stream` | boolean | 是 | `false` | 是否流式 |
| `systemMessage` | string | 否 | 配置文件中的默认提示词 | 自定义系统提示词 |
| `temperature` | number | 否 | `0.7` | 温度 |
| `maxTokens` | integer | 否 | `2048` | 最大输出 token |
| `tools` | array | 否 | - | 工具列表 |

### 请求示例

```json
{
  "message": "我想学习 Spring Boot，应该先学什么？",
  "sessionId": "session-springboot-001",
  "model": "deepseek-r1:8b",
  "stream": true,
  "temperature": 0.7,
  "maxTokens": 1024
}
```

### 响应类型

`text/event-stream`

### SSE 响应示例

```text
data: {"content":"你","model":"deepseek-r1:8b","timestamp":1744171200000,"success":true}

data: {"content":"好","model":"deepseek-r1:8b","timestamp":1744171200100,"success":true}

data: {"content":"，","model":"deepseek-r1:8b","timestamp":1744171200200,"success":true}

data: {"content":"建","model":"deepseek-r1:8b","timestamp":1744171200300,"success":true}

data: {"content":"议","model":"deepseek-r1:8b","timestamp":1744171200400,"success":true,"tokenUsage":{"promptTokens":120,"completionTokens":35,"totalTokens":155}}
```

### 重要说明

1. `X-User-Id` 的优先级高于请求体中的 `userId`
2. 如果不传 `sessionId`，后端会自动生成并创建会话
3. **但当前 `ChatResponse` 里没有 `sessionId` 字段**，所以前端如果要稳定续聊，推荐首次就自己生成 `sessionId` 并传入
4. 模型权限会在后端校验，如果前端传了无权限模型，会被拒绝

---

## 4.6 GET /api/v1/chat/history/{sessionId}

### 接口说明

查询指定会话的全部聊天历史，按创建时间升序返回。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `sessionId` | string | 是 |

### 成功响应示例

```json
[
  {
    "chatId": 1001,
    "sessionId": "session-springboot-001",
    "chatRole": "user",
    "contentType": "text",
    "status": "active",
    "content": "我想学习 Spring Boot，应该先学什么？",
    "createdAt": "2026-04-09T10:00:00"
  },
  {
    "chatId": 1002,
    "sessionId": "session-springboot-001",
    "chatRole": "assistant",
    "contentType": "text",
    "status": "completed",
    "content": "建议先掌握 Java 基础、Maven、HTTP 和 Spring 核心概念。",
    "createdAt": "2026-04-09T10:00:05"
  }
]
```

---

# 5. 会话接口

## 5.1 GET /api/v1/sessions

### 接口说明

获取指定用户的所有会话。

### Query 参数

| 参数 | 类型 | 必填 | 默认值 |
| --- | --- | --- | --- |
| `userId` | string | 否 | `default-user` |

### 成功响应

返回 `ChatSession[]`。

---

## 5.2 GET /api/v1/sessions/user/{userId}

### 接口说明

获取指定用户的所有会话，按创建时间倒序排列。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `userId` | string | 是 |

### 成功响应示例

```json
[
  {
    "sessionId": "session-springboot-001",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "userName": "zhangsan",
    "systemRole": null,
    "sessionName": "我想学习 Spring Boot，应该先学什么？",
    "sessionStatus": "active",
    "title": null,
    "createdAt": "2026-04-09T10:00:00"
  }
]
```

---

## 5.3 GET /api/v1/sessions/{sessionId}

### 接口说明

获取单个会话详情。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `sessionId` | string | 是 |

### 成功响应

返回 `ChatSession`。

---

## 5.4 PUT /api/v1/sessions/{sessionId}/rename?name=xxx

### 接口说明

重命名会话。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `sessionId` | string | 是 |

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 新会话名 |

### 成功响应

返回更新后的 `ChatSession`。

---

## 5.5 DELETE /api/v1/sessions/{sessionId}

### 接口说明

删除会话及其对应的所有聊天记录。

### 路径参数

| 参数 | 类型 | 必填 |
| --- | --- | --- |
| `sessionId` | string | 是 |

### 成功响应

- 默认返回 `200 OK`
- 无专门响应体

---

# 6. 系统与监控接口

## 6.1 GET /health

### 接口说明

应用健康检查。

### 成功响应示例

```json
{
  "status": "UP",
  "appName": "testDeepseekR1",
  "timestamp": "2026-04-09T10:00:00",
  "aiModel": "deepseek-r1:8b",
  "message": "AI聊天服务运行正常"
}
```

---

## 6.2 GET /health/ping

### 接口说明

简单联通性测试。

### 成功响应

```text
pong
```

---

## 6.3 GET /api/monitor/metrics

### 接口说明

获取实时系统指标。

### 成功响应

返回 `SystemMetrics` 对象，字段以实际后端返回为准。

---

## 6.4 GET /api/monitor/overview

### 接口说明

获取系统运行概览。

### 成功响应示例

```json
{
  "applicationName": "testDeepseekR1",
  "timestamp": "2026-04-09T10:00:00",
  "status": "RUNNING",
  "cpuProcessors": 16,
  "heapUsage": "42%",
  "activeThreads": 58,
  "daemonThreads": 41
}
```

---

## 6.5 GET /api/monitor/refresh

### 接口说明

触发重新收集并打印系统指标。

### 成功响应

```text
指标已重新收集并打印到日志
```

---

# 7. 关键 DTO 说明

## 7.1 AiModelResponse

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `modelCode` | string | 模型编码，聊天请求时传这个 |
| `displayName` | string | 前端展示名 |
| `providerCode` | string | provider 编码 |
| `apiModelName` | string | 实际调用上游 API 的模型名 |
| `level` | integer | 模型梯队，1=第一梯队，2=第二梯队 |
| `localModel` | boolean | 是否本地模型 |
| `supportsStream` | boolean | 是否支持流式 |
| `description` | string | 模型说明 |

## 7.2 ChatRequest

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `message` | string | 用户消息 |
| `sessionId` | string | 会话 ID |
| `userId` | string | 用户 ID |
| `model` | string | 模型编码 |
| `stream` | boolean | 是否流式 |
| `systemMessage` | string | 自定义系统提示词 |
| `temperature` | number | 温度 |
| `maxTokens` | integer | 最大输出 token |
| `tools` | array | 工具列表 |

## 7.3 ChatResponse

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `content` | string | AI 回复内容；流式时通常是片段 |
| `model` | string | 使用的模型 |
| `timestamp` | long | 时间戳 |
| `success` | boolean | 是否成功 |
| `errorMessage` | string | 错误信息 |
| `tokenUsage` | object | token 使用统计 |

### tokenUsage

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `promptTokens` | integer | 输入 token |
| `completionTokens` | integer | 输出 token |
| `totalTokens` | integer | 总 token |

## 7.4 ChatSession

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `userId` | string | 用户 ID |
| `userName` | string | 用户名 |
| `systemRole` | string | 系统角色 |
| `sessionName` | string | 会话名称 |
| `sessionStatus` | string | 会话状态 |
| `title` | string | 标题 |
| `createdAt` | string | 创建时间 |

## 7.5 ChatMessage

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chatId` | long | 消息 ID |
| `sessionId` | string | 会话 ID |
| `chatRole` | string | `system` / `user` / `assistant` |
| `contentType` | string | 内容类型 |
| `status` | string | 状态 |
| `content` | string | 文本内容 |
| `createdAt` | string | 创建时间 |

---

# 8. 前端联调建议

## 8.1 登录后建议缓存

- `userId`
- `identity`
- `identityLabel`
- `permissionSummary`
- `availableModels`

## 8.2 聊天接口推荐做法

### 非流式聊天

- 走 `POST /api/v1/chat/structured`
- 请求头建议带 `X-User-Id`
- `model` 从 `availableModels[].modelCode` 中选

### 流式聊天

推荐直接使用：

- `POST /api/v1/chat/structured/stream/persistent`

并且：

1. 前端自己生成 `sessionId`
2. 每轮都带同一个 `sessionId`
3. 请求头里传 `X-User-Id`
4. 模型从登录返回的可用模型列表里选

## 8.3 不建议再依赖的接口

项目里存在 `AiModelController` 类，但当前没有作为对外 REST 接口暴露，**请以前端登录接口返回的 `availableModels` 为准**，不要再设计单独的模型权限拉取流程。

---

# 9. 一个推荐的联调顺序

1. `POST /api/v1/users/login` 登录
2. 从返回结果拿 `userId` 和 `availableModels`
3. 前端生成一个 `sessionId`
4. 调 `POST /api/v1/chat/structured/stream/persistent`
5. 再调 `GET /api/v1/sessions/user/{userId}` 拉会话列表
6. 调 `GET /api/v1/chat/history/{sessionId}` 拉历史消息

---

# 10. 文档备注

- 本文档按当前项目源码整理。
- 如果后续你还要，我可以继续给你补两份：
  1. **更偏 APIpost 导入格式的精简版**（每个接口更短，更适合工具识别）
  2. **更偏前端联调说明的业务版**（更通俗，更适合发同事）

