# 后台管理系统接口文档：模型、计费、配额、企业空间

## 1. 本次改动内容

这次把原来分散在用户角色和模型授权里的逻辑，改成了可审计、可运营、可后台配置的企业级模型成本系统。

核心新增能力：

- 模型计价：`ai_model_pricing` 保存模型历史价格、货币、缓存输入折扣、平台加价倍率。
- 调用流水：`ai_usage_event` 作为 append-only 真相源，记录每次 AI 调用的 token、费用、状态、耗时、用户、workspace。
- 配额控制：`role_quota_config` 管个人角色配额，企业 workspace 使用 plan entitlement 里的团队共享额度。
- 模型授权：个人走 `role_model_default`，企业 workspace 走 `plan_entitlement`，用户级 `user_model_permission` 可做 GRANT/DENY 覆盖。
- 企业空间：新增 organization、workspace、workspace_member、user_default_workspace、plan、plan_entitlement。
- 后台 API：统一在 `/api/v1/admin` 下管理模型、价格、角色、用户、workspace、plan、用量监控。

## 2. 设计思路

### 2.1 权限优先级

模型最终可见性按以下顺序计算：

1. 先取基础规则：
   - 个人 workspace：按 `User.userRole` 查 `role_model_default`。
   - 企业 workspace：按 `workspace.planCode` 查 `plan_entitlement`。
2. 再叠加用户覆盖：
   - `GRANT` 增加模型。
   - `DENY` 移除模型。
3. 最后与 `ai_model_definition.enabled = true` 取交集。

最终规则：`DENY > GRANT > role/plan base rules`。

### 2.2 配额规则

个人用户：

- 默认每个用户都会有个人 organization + 个人 workspace。
- 个人 workspace 的 `planCode = null`。
- 配额按用户角色从 `role_quota_config` 读取。
- 用量按 `ai_usage_event.user_id + workspace_id` 聚合。

企业 workspace：

- 企业 workspace 必须设置 `planCode` 才启用企业套餐逻辑。
- 配额从 `plan_entitlement` 的 `LIMIT` 项读取。
- 用量按 `ai_usage_event.workspace_id` 聚合，是团队共享池。

### 2.3 计费规则

每次调用都落一条 `ai_usage_event`：

- 成功：`status = SUCCESS`，写入 token 和费用。
- 配额拦截：`status = BLOCKED_BY_QUOTA`，费用为 0。
- 缺少价格：`status = BLOCKED_BY_PRICING`，费用为 0。
- 上游失败：`status = FAILED`，费用为 0。

费用计算公式：

```text
regularPromptTokens = promptTokens - cachedPromptTokens
vendorCost =
  regularPromptTokens / 1_000_000 * promptPricePerMillion
  + cachedPromptTokens / 1_000_000 * cachedInputPricePerMillion
  + completionTokens / 1_000_000 * completionPricePerMillion
  + requestSurcharge

billedCost = vendorCost * markupRatio
```

如果没有缓存价格，缓存 token 会按普通输入价格计费，避免少计费；上线前建议为支持缓存的模型补齐真实缓存价格。

## 3. 通用约定

### 3.1 管理端鉴权

当前后台接口使用临时 Header：

```http
X-User-Id: <adminUserId>
```

后端会校验该用户 `userRole == ADMIN`。后续切 JWT 时，只需要替换 Admin 鉴权入口。

### 3.2 workspace 上下文

聊天接口支持可选 Header：

```http
X-Workspace-Id: <workspaceId>
```

不传时使用 `user_default_workspace`。传了则校验当前用户必须是该 workspace 的成员，否则返回 403。

部分结构化聊天请求体也支持 `workspaceId` 字段。

### 3.3 时间格式

后台接口的 `from`、`to`、`effectiveFrom`、`effectiveTo`、`expiresAt` 使用 ISO LocalDateTime：

```text
2026-05-18T10:30:00
```

### 3.4 常见错误

配额超限：

```http
HTTP 429
```

```json
{
  "success": false,
  "code": "DAILY_LIMIT_EXCEEDED",
  "message": "AI quota exceeded",
  "dailyUsed": 2.12,
  "dailyLimit": 2.00,
  "monthlyUsed": 18.45,
  "monthlyLimit": 30.00,
  "resetAt": "2026-05-19T00:00:00"
}
```

模型没有价格：

```http
HTTP 503
```

```json
{
  "success": false,
  "code": "PRICING_NOT_CONFIGURED",
  "message": "Model pricing is not configured: test-model-x"
}
```

## 4. 推荐前端页面

### 4.1 模型目录页

用途：查看所有模型、开关模型、配置模型参数和价格。

使用接口：

- `GET /api/v1/admin/models`
- `POST /api/v1/admin/models`
- `GET /api/v1/admin/models/{code}`
- `PATCH /api/v1/admin/models/{code}`
- `PATCH /api/v1/admin/models/{code}/enabled`
- `DELETE /api/v1/admin/models/{code}`
- `GET /api/v1/admin/models/{code}/pricing`
- `POST /api/v1/admin/models/{code}/pricing`
- `PATCH /api/v1/admin/models/{code}/markup-ratio`

### 4.2 角色配置页

用途：配置 USER、PRO、PLUS、PRO_PLUS、VIP、ADMIN 的额度和默认模型。

使用接口：

- `GET /api/v1/admin/roles`
- `GET /api/v1/admin/roles/{role}/quota`
- `PUT /api/v1/admin/roles/{role}/quota`
- `GET /api/v1/admin/roles/{role}/models`
- `PUT /api/v1/admin/roles/{role}/models`
- `POST /api/v1/admin/roles/{role}/models/{code}`
- `DELETE /api/v1/admin/roles/{role}/models/{code}`
- `GET /api/v1/admin/models/{code}/roles`
- `PUT /api/v1/admin/models/{code}/roles`

### 4.3 用户管理页

用途：查用户、改角色/状态、查看有效模型/有效额度、配置用户级覆盖。

使用接口：

- `GET /api/v1/admin/users`
- `GET /api/v1/admin/users/{userId}`
- `PATCH /api/v1/admin/users/{userId}/role`
- `PATCH /api/v1/admin/users/{userId}/status`
- `POST /api/v1/admin/users/{userId}/overrides/models`
- `DELETE /api/v1/admin/users/{userId}/overrides/models/{overrideId}`
- `POST /api/v1/admin/users/{userId}/overrides/quota`
- `DELETE /api/v1/admin/users/{userId}/overrides/quota/{overrideId}`
- `GET /api/v1/admin/users/{userId}/effective-models`
- `GET /api/v1/admin/users/{userId}/effective-quota`

### 4.4 Workspace / Plan 管理页

用途：管理企业空间、成员、套餐和套餐权益。

使用接口：

- `GET /api/v1/admin/workspaces`
- `POST /api/v1/admin/workspaces`
- `PATCH /api/v1/admin/workspaces/{id}`
- `POST /api/v1/admin/workspaces/{id}/members`
- `DELETE /api/v1/admin/workspaces/{id}/members/{userId}`
- `GET /api/v1/admin/plans`
- `POST /api/v1/admin/plans`
- `GET /api/v1/admin/plans/{code}/entitlements`
- `POST /api/v1/admin/plans/{code}/entitlements`
- `DELETE /api/v1/admin/plans/{code}/entitlements/{id}`

### 4.5 用量监控页

用途：查看调用明细、聚合报表、TopN、角色额度状态、单用户时间线。

使用接口：

- `GET /api/v1/admin/usage/events`
- `GET /api/v1/admin/usage/aggregate`
- `GET /api/v1/admin/usage/topN`
- `GET /api/v1/admin/usage/quota-status`
- `GET /api/v1/admin/usage/users/{userId}/timeline`

## 5. 接口详情

所有接口都需要：

```http
X-User-Id: <ADMIN 用户 id>
```

### 5.1 模型目录

#### GET /api/v1/admin/models

返回所有模型，包含 disabled 模型。

响应字段：

```json
[
  {
    "model": {
      "modelCode": "deepseek-chat",
      "displayName": "DeepSeek Chat",
      "providerCode": "deepseek",
      "apiModelName": "deepseek-chat",
      "level": 2,
      "score": 80,
      "localModel": false,
      "supportsStream": true,
      "description": "..."
    },
    "enabled": true,
    "activePricing": {
      "id": 1,
      "modelCode": "deepseek-chat",
      "currency": "CNY",
      "promptPricePerMillion": 3.000000,
      "completionPricePerMillion": 12.000000,
      "cachedInputPricePerMillion": null,
      "requestSurcharge": 0,
      "markupRatio": 1.0000,
      "effectiveFrom": "2026-05-18T10:00:00",
      "effectiveTo": null,
      "enabled": true
    },
    "last24hCalls": 128
  }
]
```

#### POST /api/v1/admin/models

创建模型，可同时创建当前价格。

```json
{
  "modelCode": "test-model-x",
  "displayName": "Test Model X",
  "providerCode": "openai",
  "apiModelName": "test-model-x",
  "level": 2,
  "score": 50,
  "localModel": false,
  "supportsStream": true,
  "enabled": true,
  "description": "测试模型",
  "pricing": {
    "currency": "CNY",
    "promptPricePerMillion": 5,
    "completionPricePerMillion": 15,
    "cachedInputPricePerMillion": 1,
    "requestSurcharge": 0,
    "markupRatio": 1.2,
    "enabled": true
  }
}
```

#### GET /api/v1/admin/models/{code}

模型详情，包含当前价格、历史价格、已开放角色、24 小时调用量。

#### PATCH /api/v1/admin/models/{code}

可修改字段：

```json
{
  "displayName": "New Name",
  "providerCode": "openai",
  "apiModelName": "new-upstream-name",
  "level": 1,
  "score": 90,
  "localModel": false,
  "supportsStream": true,
  "description": "更新说明"
}
```

#### PATCH /api/v1/admin/models/{code}/enabled

```json
{
  "enabled": false
}
```

#### DELETE /api/v1/admin/models/{code}

软删除模型，实际行为是 `enabled=false`，保留 usage event 外键和审计历史。

### 5.2 价格管理

#### GET /api/v1/admin/models/{code}/pricing

返回该模型全部历史价格，按 `effectiveFrom` 倒序。

#### POST /api/v1/admin/models/{code}/pricing

新增价格。后端会把上一条当前生效价格的 `effectiveTo` 补为当前时间。

```json
{
  "currency": "CNY",
  "promptPricePerMillion": 45,
  "completionPricePerMillion": 180,
  "cachedInputPricePerMillion": 10,
  "requestSurcharge": 0,
  "markupRatio": 1.1,
  "effectiveFrom": "2026-05-18T10:30:00",
  "enabled": true
}
```

#### PATCH /api/v1/admin/models/{code}/markup-ratio

快捷调整平台加价倍率，会生成一条新价格记录。

```json
{
  "markupRatio": 1.25
}
```

### 5.3 角色配额和模型

角色枚举：

```text
GUEST, USER, PRO, PLUS, PRO_PLUS, VIP, ADMIN
```

#### GET /api/v1/admin/roles

返回角色、配额、模型数量、用户数量。

#### GET /api/v1/admin/roles/{role}/quota

返回角色当前配额。

#### PUT /api/v1/admin/roles/{role}/quota

```json
{
  "dailyLimit": 10.00,
  "monthlyLimit": 200.00,
  "concurrentRequests": 3,
  "description": "9.9 元档"
}
```

`dailyLimit` 或 `monthlyLimit` 为 `null` 表示不限。

#### GET /api/v1/admin/roles/{role}/models

返回该角色默认可用模型列表。

#### PUT /api/v1/admin/roles/{role}/models

全量替换该角色模型。

```json
{
  "modelCodes": ["deepseek-chat", "gpt-5.4"]
}
```

#### POST /api/v1/admin/roles/{role}/models/{code}

单独授权一个模型给角色。

#### DELETE /api/v1/admin/roles/{role}/models/{code}

单独取消角色的模型授权。

#### GET /api/v1/admin/models/{code}/roles

查看某模型开放给哪些角色。

#### PUT /api/v1/admin/models/{code}/roles

替换某模型开放角色。

```json
{
  "roles": ["PRO", "PLUS", "PRO_PLUS", "VIP", "ADMIN"]
}
```

### 5.4 用户管理

#### GET /api/v1/admin/users

查询参数：

- `q`：用户名、昵称、邮箱模糊搜索。
- `role`：角色过滤。
- `page`：默认 0。
- `size`：默认 20。

#### GET /api/v1/admin/users/{userId}

返回用户基础信息、日/月配额快照、最近 20 条调用、模型覆盖、配额覆盖。

#### PATCH /api/v1/admin/users/{userId}/role

```json
{
  "role": "PRO"
}
```

#### PATCH /api/v1/admin/users/{userId}/status

```json
{
  "status": "ACTIVE"
}
```

状态值来自 `User.UserStatus`，前端应以后端返回为准。

#### POST /api/v1/admin/users/{userId}/overrides/models

新增或更新用户模型覆盖。

```json
{
  "modelCode": "claude-opus-4.7",
  "overrideType": "GRANT",
  "workspaceId": null,
  "enabled": true,
  "reason": "临时体验高阶模型",
  "expiresAt": "2026-06-01T00:00:00"
}
```

字段说明：

- `overrideType`: `GRANT` 或 `DENY`。
- `workspaceId = null`: 全局生效。
- `workspaceId != null`: 只在指定 workspace 生效。
- 同一用户、同一 workspace、同一模型只保留一条覆盖。

#### DELETE /api/v1/admin/users/{userId}/overrides/models/{overrideId}

删除用户模型覆盖。

#### POST /api/v1/admin/users/{userId}/overrides/quota

新增配额增量覆盖。

```json
{
  "workspaceId": null,
  "dailyDelta": 0,
  "monthlyDelta": 50,
  "reason": "本月补偿额度",
  "expiresAt": "2026-06-01T00:00:00"
}
```

最终配额 = 角色或套餐基础配额 + 所有未过期 delta。

#### DELETE /api/v1/admin/users/{userId}/overrides/quota/{overrideId}

删除用户配额覆盖。

#### GET /api/v1/admin/users/{userId}/effective-models

查询用户实际可用模型。

查询参数：

- `workspaceId`：可选。不传使用默认 workspace。

#### GET /api/v1/admin/users/{userId}/effective-quota

查询用户实际配额。

查询参数：

- `workspaceId`：可选。不传使用默认 workspace。

响应：

```json
{
  "daily": {
    "userId": "u1",
    "workspaceId": "w1",
    "used": 1.23,
    "limit": 10.00,
    "period": "DAILY",
    "unlimited": false
  },
  "monthly": {
    "userId": "u1",
    "workspaceId": "w1",
    "used": 30.12,
    "limit": 200.00,
    "period": "MONTHLY",
    "unlimited": false
  }
}
```

### 5.5 Workspace 管理

#### GET /api/v1/admin/workspaces

查询参数：

- `q`：workspace 名称或 ID 模糊搜索。
- `planCode`：套餐过滤。
- `page`：默认 0。
- `size`：默认 20。

#### POST /api/v1/admin/workspaces

```json
{
  "workspaceId": "optional-workspace-id",
  "orgId": "optional-org-id",
  "orgName": "红湖科技",
  "name": "红湖研发团队",
  "planCode": "team_pro",
  "status": "ACTIVE",
  "ownerUserId": "u-owner"
}
```

如果 `orgId` 不存在，后端会自动创建 organization。

#### PATCH /api/v1/admin/workspaces/{id}

```json
{
  "name": "新空间名",
  "planCode": "enterprise",
  "status": "ACTIVE"
}
```

#### POST /api/v1/admin/workspaces/{id}/members

```json
{
  "userId": "u2",
  "memberRole": "MEMBER"
}
```

推荐 memberRole：

```text
OWNER, ADMIN, MEMBER, VIEWER
```

#### DELETE /api/v1/admin/workspaces/{id}/members/{userId}

移除成员。

### 5.6 Plan 管理

#### GET /api/v1/admin/plans

返回全部套餐，按 tier 升序。

#### POST /api/v1/admin/plans

```json
{
  "planCode": "team_pro",
  "displayName": "Team Pro",
  "tier": 10,
  "description": "团队专业版",
  "enabled": true
}
```

#### GET /api/v1/admin/plans/{code}/entitlements

返回套餐权益列表。

#### POST /api/v1/admin/plans/{code}/entitlements

新增或更新套餐权益。

开放指定模型：

```json
{
  "entitlementType": "ALLOWED_MODEL",
  "entitlementKey": "gpt-5.4",
  "enabled": true
}
```

开放 provider：

```json
{
  "entitlementType": "ALLOWED_PROVIDER",
  "entitlementKey": "deepseek",
  "enabled": true
}
```

开放全部 provider：

```json
{
  "entitlementType": "ALLOWED_PROVIDER",
  "entitlementKey": "*",
  "enabled": true
}
```

配置团队日额度：

```json
{
  "entitlementType": "LIMIT",
  "entitlementKey": "DAILY_BUDGET",
  "valueNumber": 100.00,
  "enabled": true
}
```

配置团队月额度：

```json
{
  "entitlementType": "LIMIT",
  "entitlementKey": "MONTHLY_BUDGET",
  "valueNumber": 2000.00,
  "enabled": true
}
```

#### DELETE /api/v1/admin/plans/{code}/entitlements/{id}

软删除权益，实际行为是 `enabled=false`。

### 5.7 用量监控

#### GET /api/v1/admin/usage/events

查询调用流水。

查询参数：

- `userId`
- `workspaceId`
- `modelCode`
- `status`: `SUCCESS`、`FAILED`、`TIMEOUT`、`BLOCKED_BY_QUOTA`、`BLOCKED_BY_PRICING`
- `from`
- `to`
- `page`: 默认 0
- `size`: 默认 50

#### GET /api/v1/admin/usage/aggregate

聚合用量。

查询参数：

- `groupBy`: `user`、`workspace`、`model`、`provider`
- `from`
- `to`

响应：

```json
[
  {
    "dimension": "deepseek-chat",
    "calls": 120,
    "tokens": 350000,
    "cost": 8.76
  }
]
```

#### GET /api/v1/admin/usage/topN

TopN 排行。

查询参数：

- `dimension`: `user`、`workspace`、`model`、`provider`
- `metric`: `cost` 或 `tokens`
- `from`
- `to`
- `n`: 默认 10

#### GET /api/v1/admin/usage/quota-status

查看角色额度配置概览。

查询参数：

- `role`: 可选。为空返回全部角色。

响应：

```json
[
  {
    "role": "PRO",
    "userCount": 42,
    "dailyLimit": 10.00,
    "monthlyLimit": 200.00,
    "unlimited": false,
    "modelCount": 8
  }
]
```

#### GET /api/v1/admin/usage/users/{userId}/timeline

单用户调用时间线。

查询参数：

- `page`: 默认 0
- `size`: 默认 100

## 6. 前端实现建议

### 6.1 首页指标

建议展示：

- 今日调用次数。
- 今日 billed cost。
- 今日 token 数。
- 今日配额触顶用户数。
- 24 小时失败率。
- Top 5 模型成本。

数据来源：

- `GET /usage/aggregate?groupBy=model&from=当天0点`
- `GET /usage/topN?dimension=model&metric=cost&from=当天0点`
- `GET /usage/events?status=BLOCKED_BY_QUOTA&from=当天0点`

### 6.2 用户详情页

建议分 Tab：

- 基本信息：角色、状态、注册时间。
- 有效模型：调用 `effective-models`。
- 有效配额：调用 `effective-quota`。
- 覆盖规则：展示 model overrides 和 quota overrides。
- 最近调用：展示 `recentUsage` 或 `timeline`。

### 6.3 模型详情页

建议分区：

- 基础配置。
- 当前价格。
- 历史价格。
- 开放角色。
- 24h 调用量。
- 启用/禁用操作。

### 6.4 Plan 配置页

建议把 `plan_entitlement` 做成三组表单：

- 模型白名单：`ALLOWED_MODEL`。
- provider 白名单：`ALLOWED_PROVIDER`。
- 额度：`LIMIT.DAILY_BUDGET`、`LIMIT.MONTHLY_BUDGET`、`LIMIT.CONCURRENT_REQUESTS`。

## 7. 当前实现边界

这些点已经预留，但当前版本未做完整企业级闭环：

- 管理端鉴权还是 `X-User-Id`，生产建议切 JWT + 权限中间件。
- 配额采用软拦截，不做预扣和退款；流式调用可能在触顶当次略微超额。
- 用量聚合直接查 `ai_usage_event`，大规模后建议增加日/月物化聚合表或 Redis 缓存。
- 并发上限字段已建表，但 v1 暂未强制执行。
- 支付、发票、账单周期、订阅状态同步还未接入。
