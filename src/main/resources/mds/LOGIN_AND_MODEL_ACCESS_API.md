# 登录与模型权限 API 说明

> 文档用途：发给前端同学，用于对接本次“身份分层 + 模型权限 + 登录返回可用模型”改动。
>
> 当前版本重点接口：`POST /api/v1/users/login`

---

## 1. 改动背景

本次改动后，前端**不再需要单独调模型权限接口**来拿“当前用户可以使用哪些模型”。

现在统一通过登录接口返回：

- 用户基础信息
- 当前身份
- 身份中文名
- 权限摘要
- 当前身份可用模型列表

这样前端登录成功后，就可以直接：

1. 保存登录用户信息
2. 根据 `identity` / `identityLabel` 显示身份标签
3. 根据 `availableModels` 渲染模型下拉框
4. 在后续聊天接口请求头里继续传 `X-User-Id`

---

## 2. 身份规则

当前系统支持 4 种身份：

| 身份枚举 | 中文名称 | 权限说明 |
|---|---|---|
| `GUEST` | 游客 | 只能使用普通模型，当前默认限制为 **本地 + 第二梯队模型** |
| `USER` | 普通用户 | 只能使用 **第二梯队模型** |
| `VIP` | VIP用户 | 可以使用 **全部梯队模型** |
| `ADMIN` | admin用户 | 可以使用 **全部梯队模型**，并拥有全量管理权限 |

> 说明：
> - 模型的梯队由后端模型目录中的 `level` 字段控制。
> - `level=1` 表示第一梯队。
> - `level=2` 表示第二梯队。

---

## 3. 登录接口

### 3.1 接口地址

```http
POST /api/v1/users/login
Content-Type: application/json
```

---

### 3.2 请求体说明

#### 请求 JSON

```json
{
  "guestLogin": false,
  "username": "zhangsan",
  "password": "123456"
}
```

#### 请求字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `guestLogin` | `boolean` | 否 | 是否游客登录。为 `true` 时，后端按游客身份返回，可不传用户名密码 |
| `username` | `string` | 条件必填 | 普通登录时必填 |
| `password` | `string` | 条件必填 | 普通登录时必填 |

---

### 3.3 游客登录示例

#### 请求

```json
{
  "guestLogin": true
}
```

#### 说明

当 `guestLogin=true` 时：

- 后端不会校验用户名密码
- 返回一个“游客身份”的统一用户结构
- `identity = GUEST`
- `availableModels` 只会返回游客可用模型

---

## 4. 登录返回结构

### 4.1 返回 JSON 示例（普通用户）

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
      "description": "本地 Ollama 8B 模型，默认本地可用模型"
    },
    {
      "modelCode": "gpt-5-mini",
      "displayName": "GPT-5 mini",
      "providerCode": "openai",
      "apiModelName": "gpt-5-mini",
      "level": 2,
      "localModel": false,
      "supportsStream": true,
      "description": null
    }
  ],
  "homeAddress": "北京市朝阳区 XX 街道 XX 号",
  "createdAt": "2026-03-15T10:00:00",
  "updatedAt": "2026-04-01T09:30:00"
}
```

---

### 4.2 返回字段表

| 字段 | 类型 | 说明 |
|---|---|---|
| `userId` | `string` | 用户 ID |
| `username` | `string` | 用户名 |
| `nickname` | `string` | 昵称 |
| `phone` | `string` | 手机号 |
| `email` | `string` | 邮箱 |
| `gender` | `string` | 性别：`MALE` / `FEMALE` / `OTHER` |
| `userStatus` | `string` | 用户状态：`ACTIVE` / `INACTIVE` / `BANNED` |
| `userRole` | `string` | 用户角色（数据库里的身份角色） |
| `identity` | `string` | 登录后用于前端显示/权限判断的身份枚举 |
| `identityLabel` | `string` | 身份中文名称 |
| `permissionSummary` | `string` | 当前身份的权限摘要 |
| `availableModels` | `AiModelResponse[]` | 当前身份可用模型列表 |
| `homeAddress` | `string` | 家庭地址 |
| `createdAt` | `string` | 创建时间 |
| `updatedAt` | `string` | 更新时间 |

> 当前 `identity` 和 `userRole` 一般是一致的。
> 前端如果只想做展示与权限判断，建议优先使用 `identity` / `identityLabel` / `permissionSummary`。

---

## 5. `availableModels` 子项说明

每个模型项结构如下：

| 字段 | 类型 | 说明 |
|---|---|---|
| `modelCode` | `string` | 模型编码，聊天请求里传这个 |
| `displayName` | `string` | 给前端展示的模型名称 |
| `providerCode` | `string` | 提供商编码 |
| `apiModelName` | `string` | 后端真正调用上游时用的模型名 |
| `level` | `number` | 模型梯队：`1` 第一梯队，`2` 第二梯队 |
| `localModel` | `boolean` | 是否本地模型 |
| `supportsStream` | `boolean` | 是否支持流式 |
| `description` | `string` | 模型描述 |

---

## 6. 前端推荐使用方式

### 6.1 登录后建议缓存字段

建议前端缓存：

- `userId`
- `identity`
- `identityLabel`
- `permissionSummary`
- `availableModels`

---

### 6.2 模型下拉框建议使用

- 文案显示：`displayName`
- 实际提交值：`modelCode`

例如：

```ts
const options = availableModels.map(item => ({
  label: item.displayName,
  value: item.modelCode
}));
```

---

### 6.3 聊天接口调用建议

后续调用聊天接口时：

1. 请求头传 `X-User-Id`
2. 请求体里 `model` 传 `availableModels` 中选中的 `modelCode`

#### 示例

```http
POST /api/v1/chat/structured
X-User-Id: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
```

```json
{
  "message": "请帮我分析这段方案",
  "model": "gpt-5-mini",
  "stream": false
}
```

---

## 7. 兼容说明

### 7.1 这次改动后
前端**不需要再单独调用模型权限接口**来获取可用模型。

### 7.2 推荐做法
以 `login` 返回的 `availableModels` 为准。

---

## 8. 错误场景说明

### 8.1 普通登录用户名密码错误
后端会返回错误响应（具体取决于全局异常处理配置）。

### 8.2 聊天时传了无权限模型
后端会拒绝，并提示类似：

- `当前用户无权使用模型: xxx`
- `自动路由模型当前用户无权使用: xxx`

前端建议：
- 只允许从 `availableModels` 里选模型
- 不要让前端自己拼未知模型编码

---

## 9. 前端联调重点

### 登录后至少确认这 4 件事

- [ ] `identity` 是否符合预期
- [ ] `permissionSummary` 是否正确展示
- [ ] `availableModels` 是否能渲染成模型下拉框
- [ ] 选择模型后，聊天请求是否把 `modelCode` 正确传回后端

---

## 10. 当前最重要的结论

### 登录返回统一看 `UserResponse`

本次改动后，登录接口直接返回增强后的 `UserResponse`，里面已经包含：

- 用户信息
- 身份
- 权限摘要
- 可用模型列表

前端以这个结构接就可以。

