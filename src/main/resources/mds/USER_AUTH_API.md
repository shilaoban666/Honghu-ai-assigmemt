# 用户注册与登录接口文档

本文档详细说明用户注册（创建用户）和登录的接口使用方法。

---

## 目录

1. [用户注册](#一用户注册)
2. [用户登录](#二用户登录)
3. [快速开始示例](#三快速开始示例)
4. [常见问题](#四常见问题)

---

## 一、用户注册

### 1.1 接口说明

**接口路径**: `POST /api/v1/users`

**接口描述**: 创建一个新的用户账户，密码会被加密存储到数据库中。

**请求格式**: `application/json`

### 1.2 请求参数

| 字段 | 类型 | 必填 | 说明 | 示例 | 约束 |
|------|------|------|------|------|------|
| username | String | 是 | 用户名（登录名），全局唯一 | zhangsan | 长度 2-50 字符 |
| password | String | 是 | 密码（自动加密存储） | password123 | 长度 6-100 字符 |
| nickname | String | 否 | 昵称 | 张三 | 最大长度 100 |
| phone | String | 否 | 手机号，全局唯一 | 13800138000 | 有效手机号格式 |
| email | String | 否 | 邮箱，全局唯一 | zhangsan@example.com | 有效邮箱格式 |
| gender | Enum | 否 | 性别 | MALE | MALE, FEMALE, OTHER |
| userStatus | Enum | 否 | 用户状态 | ACTIVE | ACTIVE, INACTIVE, BANNED |
| homeAddress | String | 否 | 家庭地址 | 北京市朝阳区 XX 街道 | 最大长度 500 |

### 1.3 请求示例

#### 最简示例（仅必填字段）

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "password123"
  }'
```

#### 完整示例（所有字段）

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "password123",
    "nickname": "张三",
    "phone": "13800138000",
    "email": "zhangsan@example.com",
    "gender": "MALE",
    "userStatus": "ACTIVE",
    "homeAddress": "北京市朝阳区 XX 街道 XX 号"
  }'
```

#### Postman 配置

```
Method: POST
URL: http://localhost:8080/api/v1/users
Headers:
  Content-Type: application/json
Body (raw JSON):
{
  "username": "zhangsan",
  "password": "password123",
  "nickname": "张三",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "gender": "MALE",
  "userStatus": "ACTIVE",
  "homeAddress": "北京市朝阳区 XX 街道 XX 号"
}
```

### 1.4 成功响应

**状态码**: `201 Created`

**响应示例**:

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "zhangsan",
  "nickname": "张三",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "gender": "MALE",
  "userStatus": "ACTIVE",
  "homeAddress": "北京市朝阳区 XX 街道 XX 号",
  "createdAt": "2026-03-15T10:00:00",
  "updatedAt": "2026-03-15T10:00:00"
}
```

**注意**: 
- 系统会自动生成 UUID 作为 userId
- 密码会被自动加密存储，不会在响应中返回
- 如果未指定 gender，默认为 OTHER
- 如果未指定 userStatus，默认为 ACTIVE

### 1.5 错误响应

#### 400 - 参数错误

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "用户名不能为空",
  "path": "/api/v1/users"
}
```

#### 409 - 用户名已存在

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "用户名已存在：zhangsan",
  "path": "/api/v1/users"
}
```

#### 409 - 手机号已存在

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "手机号已存在：13800138000",
  "path": "/api/v1/users"
}
```

#### 409 - 邮箱已存在

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "邮箱已存在：zhangsan@example.com",
  "path": "/api/v1/users"
}
```

---

## 二、用户登录

### 2.1 接口说明

**接口路径**: `POST /api/v1/users/login`

**接口描述**: 使用用户名和密码进行登录验证，验证成功后返回用户信息（不包含密码）。

**请求格式**: `application/json`

### 2.2 请求参数

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| username | String | 是 | 用户名（登录名） | zhangsan |
| password | String | 是 | 密码 | password123 |

### 2.3 请求示例

```bash
curl -X POST http://localhost:8080/api/v1/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "password123"
  }'
```

#### Postman 配置

```
Method: POST
URL: http://localhost:8080/api/v1/users/login
Headers:
  Content-Type: application/json
Body (raw JSON):
{
  "username": "zhangsan",
  "password": "password123"
}
```

### 2.4 成功响应

**状态码**: `200 OK`

**响应示例**:

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "zhangsan",
  "nickname": "张三",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "gender": "MALE",
  "userStatus": "ACTIVE",
  "homeAddress": "北京市朝阳区 XX 街道 XX 号",
  "createdAt": "2026-03-15T10:00:00",
  "updatedAt": "2026-03-15T10:00:00"
}
```

**注意**: 
- 登录成功后返回完整的用户信息
- **密码不会包含在响应中**（安全考虑）
- 可以通过返回的信息进行后续的会话管理

### 2.5 错误响应

#### 400 - 参数缺失

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "用户名不能为空",
  "path": "/api/v1/users/login"
}
```

#### 404 - 用户不存在

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "用户不存在：zhangsan",
  "path": "/api/v1/users/login"
}
```

#### 400 - 密码错误

```json
{
  "timestamp": "2026-03-15T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "密码错误",
  "path": "/api/v1/users/login"
}
```

---

## 三、快速开始示例

### 3.1 完整的注册登录流程

```bash
# 步骤 1: 注册新用户
echo "=== 步骤 1: 注册新用户 ==="
REGISTER_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "test123456",
    "nickname": "测试用户",
    "email": "test@example.com"
  }')

echo "注册响应:"
echo $REGISTER_RESPONSE | jq .

# 提取 userId（可选）
USER_ID=$(echo $REGISTER_RESPONSE | jq -r '.userId')
echo "用户 ID: $USER_ID"

# 步骤 2: 使用刚注册的账号登录
echo -e "\n=== 步骤 2: 用户登录 ==="
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "test123456"
  }')

echo "登录响应:"
echo $LOGIN_RESPONSE | jq .

# 步骤 3: 使用登录返回的用户 ID 查询用户详情
echo -e "\n=== 步骤 3: 查询用户详情 ==="
curl -s -X GET "http://localhost:8080/api/v1/users/$USER_ID" | jq .
```

### 3.2 Windows PowerShell 示例

```powershell
# 步骤 1: 注册用户
Write-Host "=== 注册用户 ==="
$registerBody = @{
    username = "newuser"
    password = "password123"
    nickname = "新用户"
    email = "newuser@example.com"
} | ConvertTo-Json

$registerResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/users" `
    -Method POST `
    -ContentType "application/json" `
    -Body $registerBody

Write-Host "注册成功！"
$registerResponse | ConvertTo-Json

# 步骤 2: 登录
Write-Host "`n=== 用户登录 ==="
$loginBody = @{
    username = "newuser"
    password = "password123"
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/users/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body $loginBody

Write-Host "登录成功！"
$loginResponse | ConvertTo-Json
```

### 3.3 Python 示例

```python
import requests
import json

BASE_URL = "http://localhost:8080/api/v1"

# 1. 注册用户
print("=== 注册用户 ===")
register_data = {
    "username": "python_user",
    "password": "python123",
    "nickname": "Python 用户",
    "email": "python@example.com"
}

response = requests.post(f"{BASE_URL}/users", json=register_data)
print(f"注册状态码：{response.status_code}")
print(f"注册响应：{json.dumps(response.json(), indent=2, ensure_ascii=False)}")

# 2. 登录
print("\n=== 用户登录 ===")
login_data = {
    "username": "python_user",
    "password": "python123"
}

response = requests.post(f"{BASE_URL}/users/login", json=login_data)
print(f"登录状态码：{response.status_code}")
print(f"登录响应：{json.dumps(response.json(), indent=2, ensure_ascii=False)}")

if response.status_code == 200:
    user_info = response.json()
    print(f"\n登录成功！欢迎，{user_info['nickname']}")
else:
    print(f"登录失败：{response.json().get('message', '未知错误')}")
```

### 3.4 Java 示例（使用 RestTemplate）

```java
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.HashMap;
import java.util.Map;

public class UserAuthExample {
    
    public static void main(String[] args) {
        RestTemplate restTemplate = new RestTemplate();
        String baseUrl = "http://localhost:8080/api/v1";
        
        // 1. 注册用户
        System.out.println("=== 注册用户 ===");
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("username", "java_user");
        registerRequest.put("password", "java123");
        registerRequest.put("nickname", "Java 用户");
        registerRequest.put("email", "java@example.com");
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> registerEntity = new HttpEntity<>(registerRequest, headers);
        
        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(
            baseUrl + "/users",
            registerEntity,
            Map.class
        );
        
        System.out.println("注册状态码：" + registerResponse.getStatusCode());
        System.out.println("注册响应：" + registerResponse.getBody());
        
        // 2. 登录
        System.out.println("\n=== 用户登录 ===");
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", "java_user");
        loginRequest.put("password", "java123");
        
        HttpEntity<Map<String, String>> loginEntity = new HttpEntity<>(loginRequest, headers);
        
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
            baseUrl + "/users/login",
            loginEntity,
            Map.class
        );
        
        System.out.println("登录状态码：" + loginResponse.getStatusCode());
        System.out.println("登录响应：" + loginResponse.getBody());
        
        if (loginResponse.getStatusCode().is2xxSuccessful()) {
            Map userInfo = loginResponse.getBody();
            System.out.println("\n登录成功！欢迎，" + userInfo.get("nickname"));
        }
    }
}
```

---

## 四、常见问题

### Q1: 密码有什么要求？

**A**: 
- 最小长度：6 个字符
- 最大长度：100 个字符
- 建议使用字母、数字、特殊字符的组合
- 密码会以加密形式存储（BCrypt）

### Q2: 用户名、手机号、邮箱重复会怎样？

**A**: 
- 这三个字段都是全局唯一的
- 如果重复，会返回 400 错误，提示"xxx 已存在"
- 建议在注册前先检查是否已存在

### Q3: 登录失败有哪些可能？

**A**: 
1. **用户不存在** - 返回 404，提示"用户不存在：xxx"
2. **密码错误** - 返回 400，提示"密码错误"
3. **参数缺失** - 返回 400，提示"用户名/密码不能为空"

### Q4: 如何测试这些接口？

**A**: 
1. **Swagger UI**: 访问 `http://localhost:8080/swagger-ui.html`
2. **Postman**: 导入上面的 Postman 配置示例
3. **curl**: 使用提供的 curl 命令
4. **浏览器插件**: 如 Advanced REST Client

### Q5: 注册后可以直接登录吗？

**A**: 
可以！注册成功后立即可以使用刚才的用户名和密码登录。

### Q6: 密码是明文存储的吗？

**A**: 
不是！密码使用 BCrypt 算法加密存储，即使数据库泄露也无法直接获取原始密码。

### Q7: 登录成功后返回的 userId 有什么用？

**A**: 
- userId 是用户的唯一标识符
- 可用于后续的用户相关操作（如查询、更新、删除）
- 可以作为会话管理的依据

### Q8: 支持第三方登录吗？

**A**: 
当前版本仅支持用户名密码登录，暂不支持微信、QQ、GitHub 等第三方登录。

---

## 附录：枚举值说明

### Gender（性别）

| 值 | 说明 |
|----|------|
| MALE | 男 |
| FEMALE | 女 |
| OTHER | 其他 |

### UserStatus（用户状态）

| 值 | 说明 |
|----|------|
| ACTIVE | 活跃（正常状态） |
| INACTIVE | 未激活（需要激活） |
| BANNED | 已封禁（禁止登录） |

---

**文档版本**: 1.0  
**最后更新**: 2026-03-15  
**基础路径**: `/api/v1`  
**Swagger 地址**: `http://localhost:8080/swagger-ui.html`
