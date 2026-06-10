# 后台管理前端配合说明：Token 额度、硬删除与 RAG 监控

## 1. 展示口径

后台现在同时返回金额和 Token，两者用途不同：

- **标准 Token**：前端主要展示口径，适合给管理员看额度、剩余量、百分比和进度条。
- **金额 CNY**：真实成本与账单口径，适合放在副信息里。
- **原始 Token**：模型真实返回的 prompt/completion token，用于排查模型返回和审计。

换算规则：

```text
1 CNY = 1,000,000 标准 Token
标准 Token 消耗 = cost_billed * 1,000,000
```

高级模型不需要单独配置“倍率”。因为高级模型单价更高，同样 1000 个原始 token 会产生更高 `cost_billed`，自然会折算成更多标准 Token，看起来就是消耗更快。

## 2. 配额字段

用户详情、用户列表和有效配额接口里的 `QuotaSnapshot` 会返回：

```json
{
  "used": 1.25,
  "limit": 2.00,
  "moneyUsed": 1.25,
  "moneyLimit": 2.00,
  "rawTokenUsed": 18342,
  "tokenUsed": 1250000,
  "tokenLimit": 2000000,
  "tokenRemaining": 750000,
  "usagePercent": 62.50,
  "period": "DAILY",
  "unlimited": false
}
```

前端展示建议：

- 主标题：`tokenUsed / tokenLimit`
- 进度条：`usagePercent`
- 副标题：`剩余 tokenRemaining`
- 成本副信息：`moneyUsed / moneyLimit`
- 审计副信息：`rawTokenUsed`

`unlimited=true` 时进度条可以满格或弱化展示，文字显示“不限”。

## 3. 新增和调整接口

### 管理员登录

```http
POST /api/v1/admin/auth/login
GET  /api/v1/admin/auth/me
POST /api/v1/admin/auth/logout
```

登录成功后前端保存返回的 token，后续请求统一加：

```http
Authorization: Bearer <token>
```

### 模型硬删除

```http
DELETE /api/v1/admin/models/{code}/hard
```

硬删除会清理：

- 模型定义
- 模型价格
- 角色默认授权
- 用户模型 override
- 套餐里 `ALLOWED_MODEL` 权益
- 该模型的调用流水

前端必须做二次确认，文案要提示“不可恢复”。普通 `DELETE /models/{code}` 仍是下架/软删除。

### 用量聚合

```http
GET /api/v1/admin/usage/aggregate?groupBy=model
GET /api/v1/admin/usage/topN?dimension=model&metric=standardTokens
GET /api/v1/admin/usage/quota-status
```

聚合行新增：

```json
{
  "dimension": "deepseek-chat",
  "calls": 12,
  "tokens": 18000,
  "cost": 0.036,
  "standardTokens": 36000,
  "failedCalls": 1,
  "blockedCalls": 0,
  "successCalls": 11
}
```

前端图表默认按 `standardTokens` 排序和绘图，金额作为辅助列。

### RAG 监控

```http
GET /api/v1/admin/rag/overview
GET /api/v1/admin/rag/documents?q=&ownerFolder=&sessionId=&fileId=&status=&page=0&size=30
```

`/rag/overview` 返回：

```json
{
  "documentCount": 22,
  "chunkCount": 126,
  "estimatedTokens": 45000,
  "declaredChunkCount": 126,
  "documentStatus": [{ "dimension": "INDEXED", "count": 20 }],
  "eventFileStatus": [{ "dimension": "SUCCESS", "count": 20 }],
  "eventRagStatus": [{ "dimension": "INDEXED", "count": 20 }],
  "ownerUsage": [{ "dimension": "userA", "documents": 8, "bytes": 123456 }],
  "recentEvents": []
}
```

RAG 页面建议包含：

- 文档数、chunk 数、估算 Token、声明 chunk 数指标卡
- 文档状态图
- 用户/目录文件分布图
- 上传事件状态图
- RAG 管道阶段图
- 文档检索表格
- 最近摄取事件表格

## 4. 页面调整建议

- 仪表盘：主指标展示“今日标准 Token”，金额放在副信息。
- 用户管理：日/月额度使用进度条展示剩余百分比。
- 角色管理：额度列显示“标准 Token / 金额”。
- Workspace：套餐列必须显示 plan code 或套餐名；`planCode` 为空时显示“个人空间”。
- 用量监控：每个 tab 下方都放图表，默认用标准 Token 观察消耗速度。
- 模型目录：保留下架/禁用，同时提供硬删除入口。
