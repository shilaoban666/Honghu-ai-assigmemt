    已使用: {} MB# RAG系统接口文档

## 概述

RAG（Retrieval-Augmented Generation）系统是基于检索增强生成的问答系统，本文档描述了文件上传相关的API接口。

**基础路径**: `/api/v1/rag`

---

## 接口列表

### 1. 获取文件上传预签名 URL

#### 接口信息

- **接口名称**: 获取文件上传预签名 URL
- **请求方法**: `POST`
- **接口路径**: `/api/v1/rag/{userId}/upload-url`
- **Content-Type**: `application/json`

#### 接口描述

根据用户名、会话ID、文件类型自动生成对应的S3存储路径，并返回一个带有效期的预签名上传URL。前端获取该URL后，使用HTTP PUT方法将文件直接上传到S3。

**文件存储路径规则**:
```
{bucket}/{username}/{sessionId}/{fileType}/{uuid}/{fileName}
```

**示例路径**:
```
honghu-ai-document-upload/admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf
```

#### 路径参数

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| userId | String | 是 | 用户名 | `admin` |

#### 请求体参数

**请求对象**: `UploadUrlRequest`

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| sessionId | String | 是 | 会话ID | `sess_abc123` |
| fileType | String | 是 | 文件类型/扩展名（如：pdf, doc, txt, jpg等） | `pdf` |
| fileName | String | 否 | 原始文件名 | `report.pdf` |
| fileSize | Long | 否 | 文件尺寸（字节） | `54544` |

#### 请求示例

```json
{
  "sessionId": "sess_abc123",
  "fileType": "pdf",
  "fileName": "report.pdf",
  "fileSize": 54544
}
```

#### 响应参数

**响应对象**: `UploadUrlResponse`

| 参数名 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| uploadUrl | String | 预签名上传URL，前端使用HTTP PUT方法直传文件到该地址 | `https://s3.amazonaws.com/...` |
| objectKey | String | 文件在S3中的完整路径key，上传完成后必须保存此字段，后续调用其他接口时需要携带 | `admin/sess_abc123/pdf/550e8400-xxx/report.pdf` |
| contentType | String | 文件MIME类型，前端PUT时必须在Content-Type请求头中携带此值，否则预签名校验失败 | `application/pdf` |
| fileType | String | 文件扩展名（小写） | `pdf` |
| expirationMinutes | Integer | URL有效时长（分钟），超时后需重新获取 | `30` |
| fileId | String | 文件的UUID | `550e8400-e29b-41d4-a716-446655440000` |

#### 响应示例

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "uploadUrl": "https://honghu-ai-document-upload.s3.amazonaws.com/admin/sess_abc123/550e8400-e29b-41d4-a716-446655440000/pdf/report.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...",
    "objectKey": "admin/sess_abc123/pdf/550e8400-e29b-41d4-a716-446655440000/report.pdf",
    "contentType": "application/pdf",
    "fileType": "pdf",
    "expirationMinutes": 30,
    "fileId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

#### 使用流程

1. **第一步**: 调用本接口获取预签名URL
   
   ```bash
   POST /api/v1/rag/admin/upload-url
   Content-Type: application/json
   
   {
     "sessionId": "sess_abc123",
     "fileType": "pdf",
     "fileName": "report.pdf"
   }
   ```

2. **第二步**: 使用HTTP PUT方法上传文件到S3
   
   ```bash
   PUT <uploadUrl>
   Content-Type: <contentType>
   
   <binary file data>
   ```
   
   **注意**: 
   - 必须使用第一步返回的`contentType`作为请求头
   - 直接使用二进制文件数据作为请求体

3. **第三步**: 保存`objectKey`和`fileId`，用于后续的文件处理或查询操作

#### 注意事项

1. **URL有效期**: 预签名URL有有效期限制（默认30分钟），超时后需要重新获取
2. **Content-Type匹配**: 上传文件时，请求头的Content-Type必须与返回的contentType完全一致，否则S3会拒绝上传
3. **objectKey保存**: 前端必须保存返回的objectKey，后续调用文件确认或其他相关接口时需要传递此参数
4. **文件路径唯一性**: 每次调用都会生成唯一的UUID，确保文件路径不冲突
5. **文件大小限制**: 建议在请求前检查文件大小，避免上传超大文件

#### 错误码

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误（如sessionId或fileType为空） |
| 401 | 未授权访问 |
| 500 | 服务器内部错误（如S3服务异常） |

---

## 附录

### 常见文件类型与MIME类型对照

| 文件类型 | MIME类型 |
|----------|----------|
| pdf | application/pdf |
| doc | application/msword |
| docx | application/vnd.openxmlformats-officedocument.wordprocessingml.document |
| txt | text/plain |
| jpg/jpeg | image/jpeg |
| png | image/png |
| xls | application/vnd.ms-excel |
| xlsx | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet |
| ppt | application/vnd.ms-powerpoint |
| pptx | application/vnd.openxmlformats-officedocument.presentationml.presentation |

### 技术架构说明

- **存储服务**: Amazon S3
- **签名算法**: AWS Signature Version 4 (SigV4)
- **上传方式**: 客户端直传（Client-Side Direct Upload）
- **安全机制**: 预签名URL + 时效控制

---

**文档版本**: v1.0  
**最后更新**: 2026-04-17  
**维护者**: shilaoban
