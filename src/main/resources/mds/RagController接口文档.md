# RagController API 接口文档

## 1. 概述

本文档描述 `RagController` 暴露的 RAG 文件上传、登记、状态查询与 SSE 状态订阅接口。

基础路径：

```http
/api/v1/rag
```

统一鉴权请求头：

```http
X-User-Id: <userId>
```

说明：

- 所有接口都需要携带 `X-User-Id`。
- `POST /{userId}/upload-url` 要求路径中的 `userId` 与请求头 `X-User-Id` 一致。
- 文件登记、状态查询、SSE 订阅都会做用户归属校验，禁止跨用户访问。
- 上传采用“后端生成 S3 预签名 URL，前端直传 S3，再通知后端登记”的三步流程。

## 2. 上传与状态流程

1. 前端调用 `POST /api/v1/rag/{userId}/upload-url` 获取预签名上传 URL。
2. 前端使用 `PUT <uploadUrl>` 将文件二进制直传到 S3。
3. 前端调用 `POST /api/v1/rag/files` 登记已上传文件，后端创建或补齐 `RECEIVED` 状态记录。
4. 前端通过 `GET /api/v1/rag/files/{fileId}/status` 轮询状态，或通过 `GET /api/v1/rag/files/{fileId}/status/stream` 订阅 SSE 状态流。

S3 objectKey 约定格式：

```text
{username}/{sessionId}/{fileType}/{fileId}/{fileName}
```

示例：

```text
admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf
```

## 3. 获取文件上传预签名 URL

### 3.1 接口信息

```http
POST /api/v1/rag/{userId}/upload-url
Content-Type: application/json
X-User-Id: <userId>
```

用途：根据用户、会话、文件类型和文件名生成 S3 预签名上传 URL。

### 3.2 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `userId` | `string` | 是 | 目标用户 ID，必须等于请求头 `X-User-Id` |

### 3.3 请求体 `UploadUrlRequest`

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---:|---|---|
| `sessionId` | `string` | 是 | 会话 ID | `sess_abc123` |
| `fileType` | `string` | 是 | 文件扩展名，后端会转小写并校验白名单 | `pdf` |
| `fileSize` | `number` | 否 | 文件大小，单位字节；当前 DTO 接收该字段，但生成 URL 逻辑未使用 | `54544` |
| `fileName` | `string` | 否 | 原始文件名；为空时后端使用 `{fileId}.{fileType}` | `report.pdf` |

请求示例：

```json
{
  "sessionId": "sess_abc123",
  "fileType": "pdf",
  "fileSize": 54544,
  "fileName": "report.pdf"
}
```

### 3.4 响应体 `UploadUrlResponse`

| 字段 | 类型 | 说明 |
|---|---|---|
| `uploadUrl` | `string` | S3 预签名上传 URL，前端使用 `PUT` 直传文件 |
| `objectKey` | `string` | S3 对象路径，上传完成后登记接口必须传回 |
| `contentType` | `string` | 上传时必须使用的 `Content-Type` |
| `fileType` | `string` | 文件扩展名，小写 |
| `expirationMinutes` | `number` | URL 有效时长，单位分钟 |
| `fileId` | `string` | 本次上传生成的文件 UUID |

响应示例：

```json
{
  "uploadUrl": "https://honghu-ai-document-upload.s3.amazonaws.com/admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&...",
  "objectKey": "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf",
  "contentType": "application/pdf",
  "fileType": "pdf",
  "expirationMinutes": 30,
  "fileId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 3.5 支持的上传文件类型

预签名 URL 接口支持的扩展名：

```text
pdf, png, jpg, jpeg, gif, bmp, webp,
doc, docx, xls, xlsx, ppt, pptx,
txt, csv, md, json, xml
```

常见 `Content-Type`：

| 扩展名 | Content-Type |
|---|---|
| `pdf` | `application/pdf` |
| `png` | `image/png` |
| `jpg` / `jpeg` | `image/jpeg` |
| `doc` | `application/msword` |
| `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `xls` | `application/vnd.ms-excel` |
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `ppt` | `application/vnd.ms-powerpoint` |
| `pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| `txt` | `text/plain` |
| `csv` | `text/csv` |
| `md` | `text/markdown` |
| `json` | `application/json` |
| `xml` | `application/xml` |

## 4. 登记已上传文件

### 4.1 接口信息

```http
POST /api/v1/rag/files
Content-Type: application/json
X-User-Id: <userId>
```

用途：前端完成 S3 `PUT` 直传后，通知后端登记文件主记录。该接口会先创建或补齐一条 `RECEIVED` 状态记录，方便前端立刻查询状态。

### 4.2 请求体 `RegisterUploadedFileRequest`

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---:|---|---|
| `objectKey` | `string` | 是 | S3 objectKey，必须符合 `{username}/{sessionId}/{fileType}/{fileId}/{fileName}` | `admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf` |
| `fileName` | `string` | 否 | 原始文件名；为空时后端从 objectKey 最后一段解析 | `report.pdf` |

请求示例：

```json
{
  "objectKey": "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf",
  "fileName": "report.pdf"
}
```

### 4.3 响应

成功时返回 `201 Created`，响应体为 `RagFileStatusResponse`。

响应示例：

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "sess_abc123",
  "fileName": "report.pdf",
  "fileType": "pdf",
  "bucketName": "honghu-ai-document-upload",
  "objectKey": "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf",
  "documentStatus": "RECEIVED",
  "ingestionStatus": null,
  "ragStatus": null,
  "completed": false,
  "availableForChat": false,
  "chunkCount": null,
  "extractedCharacterCount": null,
  "fileSize": null,
  "detailMessage": "文件已登记，等待 SQS 异步摄取",
  "lastIndexedAt": null,
  "updatedAt": "2026-04-18T10:30:00",
  "createdAt": "2026-04-18T10:30:00"
}
```

## 5. 查询单文件 RAG 状态

### 5.1 接口信息

```http
GET /api/v1/rag/files/{fileId}/status
X-User-Id: <userId>
```

用途：查询单个文件当前是否完成解析、分块、索引，以及是否可用于聊天检索。

### 5.2 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `fileId` | `string` | 是 | 文件唯一 ID |

### 5.3 响应

成功返回 `200 OK`，响应体为 `RagFileStatusResponse`。字段说明见第 8 节。

## 6. 查询会话下文件状态列表

### 6.1 接口信息

```http
GET /api/v1/rag/sessions/{sessionId}/files
X-User-Id: <userId>
```

用途：查询某个会话下全部 RAG 文件的最新状态列表。`sessionId` 必须归属当前 `X-User-Id`。

### 6.2 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `sessionId` | `string` | 是 | 会话 ID |

### 6.3 响应

成功返回 `200 OK`，响应体为 `RagFileStatusResponse[]`。

响应示例：

```json
[
  {
    "fileId": "550e8400-e29b-41d4-a716-446655440000",
    "sessionId": "sess_abc123",
    "fileName": "report.pdf",
    "fileType": "pdf",
    "bucketName": "honghu-ai-document-upload",
    "objectKey": "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf",
    "documentStatus": "INDEXED",
    "ingestionStatus": "SUCCESS",
    "ragStatus": "SUCCESS",
    "completed": true,
    "availableForChat": true,
    "chunkCount": 12,
    "extractedCharacterCount": 8456,
    "fileSize": 53210,
    "detailMessage": "文档已建立索引，可用于聊天检索",
    "lastIndexedAt": "2026-04-18T10:31:20",
    "updatedAt": "2026-04-18T10:31:20",
    "createdAt": "2026-04-18T10:30:00"
  }
]
```

## 7. 订阅单文件 RAG 状态流

### 7.1 接口信息

```http
GET /api/v1/rag/files/{fileId}/status/stream?timeoutSeconds=60&pollIntervalMillis=1000
Accept: text/event-stream
X-User-Id: <userId>
```

用途：通过 SSE 持续推送单文件处理状态。接口会先立即推送一帧当前状态；如果文件已经进入终态，会推送后直接结束连接。

### 7.2 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `fileId` | `string` | 是 | 文件唯一 ID |

### 7.3 Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `timeoutSeconds` | `number` | 否 | `60` | SSE 单连接最长等待秒数；服务端会限制在 `5` 到 `600` 秒之间 |
| `pollIntervalMillis` | `number` | 否 | `1000` | 后端轮询状态的间隔毫秒；最小值为 `500` |

### 7.4 SSE 事件

| 事件名 | data 类型 | 说明 |
|---|---|---|
| `rag-status` | `RagFileStatusResponse` | 状态首帧或状态变化时推送 |
| `timeout` | `RagFileStatusResponse` | 等到超时时推送最后一次状态，然后结束连接 |

SSE 示例：

```text
event: rag-status
data: {"fileId":"550e8400-e29b-41d4-a716-446655440000","documentStatus":"PROCESSING","ingestionStatus":"PROCESSING","ragStatus":"CHUNKING","completed":false,"availableForChat":false}

event: rag-status
data: {"fileId":"550e8400-e29b-41d4-a716-446655440000","documentStatus":"INDEXED","ingestionStatus":"SUCCESS","ragStatus":"SUCCESS","completed":true,"availableForChat":true}
```

前端示例：

```ts
const source = new EventSource(
  "/api/v1/rag/files/550e8400-e29b-41d4-a716-446655440000/status/stream?timeoutSeconds=60&pollIntervalMillis=1000"
);

source.addEventListener("rag-status", event => {
  const status = JSON.parse(event.data);
  if (status.completed) {
    source.close();
  }
});

source.addEventListener("timeout", event => {
  const latestStatus = JSON.parse(event.data);
  source.close();
});
```

注意：原生 `EventSource` 不能直接设置自定义请求头。如果前端必须传 `X-User-Id`，可以使用支持自定义 header 的 SSE polyfill，或由后端增加 cookie/token 鉴权适配。

## 8. 公共响应对象

### 8.1 `RagFileStatusResponse`

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileId` | `string` | 上传时生成的文件唯一标识 |
| `sessionId` | `string` | 所属会话 ID |
| `fileName` | `string` | 原始文件名 |
| `fileType` | `string` | 文件扩展名 |
| `bucketName` | `string` | S3 桶名 |
| `objectKey` | `string` | S3 objectKey |
| `documentStatus` | `string` | 文档主记录状态，见第 8.2 节 |
| `ingestionStatus` | `string` | SQS 摄取事件整体状态，见第 8.3 节 |
| `ragStatus` | `string` | RAG 内部阶段状态，见第 8.4 节 |
| `completed` | `boolean` | 是否进入终态 |
| `availableForChat` | `boolean` | 是否可用于聊天检索 |
| `chunkCount` | `number` | 分块数量，成功索引后通常有值 |
| `extractedCharacterCount` | `number` | 抽取出的字符总数 |
| `fileSize` | `number` | 文件大小，单位字节 |
| `detailMessage` | `string` | 给前端展示的状态说明；失败态会脱敏 |
| `lastIndexedAt` | `string` | 最后一次成功索引时间 |
| `updatedAt` | `string` | 最近更新时间 |
| `createdAt` | `string` | 记录创建时间 |

### 8.2 `documentStatus`

| 值 | 说明 |
|---|---|
| `RECEIVED` | 已登记，等待处理 |
| `PROCESSING` | 正在处理中 |
| `INDEXED` | 已建立索引 |
| `FAILED` | 处理失败 |
| `SKIPPED` | 已跳过处理 |

### 8.3 `ingestionStatus`

| 值 | 说明 |
|---|---|
| `RECEIVED` | SQS 事件已接收 |
| `PROCESSING` | SQS 事件处理中 |
| `SUCCESS` | SQS 事件处理成功 |
| `FAILED` | SQS 事件处理失败 |
| `SKIPPED` | SQS 事件已跳过 |

### 8.4 `ragStatus`

| 值 | 说明 |
|---|---|
| `RECEIVED` | 已接收 |
| `PARSING` | 正在解析消息或路径 |
| `DOWNLOADING` | 正在下载文件 |
| `EXTRACTING` | 正在抽取文本 |
| `CHUNKING` | 正在分块 |
| `EMBEDDING` | 正在生成向量 |
| `INDEXING` | 正在写入索引 |
| `SUCCESS` | RAG 处理成功 |
| `SKIPPED` | RAG 处理跳过 |
| `FAILED` | RAG 处理失败 |

### 8.5 `completed` 与 `availableForChat`

`completed=true` 的条件：

- `documentStatus` 为 `INDEXED`、`FAILED`、`SKIPPED` 之一；或
- `ingestionStatus` 为 `SUCCESS`、`FAILED`、`SKIPPED` 之一。

`availableForChat=true` 的条件：

- `documentStatus=INDEXED`；并且
- `ingestionStatus` 为空或等于 `SUCCESS`。

## 9. 错误响应

| HTTP 状态码 | 场景 |
|---|---|
| `400 Bad Request` | 参数非法，例如缺少必填字段、不支持的文件类型、objectKey 格式不符合约定 |
| `401 Unauthorized` | 缺少 `X-User-Id` 请求头 |
| `403 Forbidden` | `X-User-Id` 与路径用户不一致，或访问/登记了不属于自己的资源 |
| `404 Not Found` | 文件状态不存在 |
| `500 Internal Server Error` | 服务端内部状态异常或依赖服务不可用 |

错误体格式取决于 Spring Boot 默认错误处理和项目全局异常处理配置，前端建议优先根据 HTTP 状态码处理。

## 10. cURL 示例

获取预签名 URL：

```bash
curl -X POST "http://localhost:8080/api/v1/rag/user-001/upload-url" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-001" \
  -d "{\"sessionId\":\"sess_abc123\",\"fileType\":\"pdf\",\"fileName\":\"report.pdf\",\"fileSize\":54544}"
```

上传文件到 S3：

```bash
curl -X PUT "<uploadUrl>" \
  -H "Content-Type: application/pdf" \
  --data-binary "@report.pdf"
```

登记文件：

```bash
curl -X POST "http://localhost:8080/api/v1/rag/files" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-001" \
  -d "{\"objectKey\":\"admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf\",\"fileName\":\"report.pdf\"}"
```

查询状态：

```bash
curl "http://localhost:8080/api/v1/rag/files/550e8400-e29b-41d4-a716-446655440000/status" \
  -H "X-User-Id: user-001"
```

查询会话文件列表：

```bash
curl "http://localhost:8080/api/v1/rag/sessions/sess_abc123/files" \
  -H "X-User-Id: user-001"
```

订阅 SSE：

```bash
curl -N "http://localhost:8080/api/v1/rag/files/550e8400-e29b-41d4-a716-446655440000/status/stream?timeoutSeconds=60&pollIntervalMillis=1000" \
  -H "X-User-Id: user-001" \
  -H "Accept: text/event-stream"
```

## 11. 前端对接注意事项

- `PUT <uploadUrl>` 时必须使用预签名接口返回的 `contentType`，否则 S3 签名校验可能失败。
- `objectKey` 是登记接口的关键字段，前端必须保存预签名接口返回值。
- 登记接口成功后即可展示文件条目，状态初始通常为 `RECEIVED`。
- 文件进入 `INDEXED` 且 `availableForChat=true` 后，才建议允许在聊天中使用该文件内容做检索增强。
- 失败态的 `detailMessage` 已做脱敏，详细错误需要查看服务端日志。

---

文档版本：v2.0  
最后更新：2026-05-03  
维护者：shilaoban
