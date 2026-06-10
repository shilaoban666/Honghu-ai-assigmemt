# Honghu AI — AWS 部署手册（EC2 + Docker + S3/SQS + CloudFront）

后端部署到一台 **EC2（t3.large）**，用 Docker Compose 跑 `app + postgres + redis + milvus`，
通过 **EC2 IAM Role** 访问 S3/SQS（无明文密钥），nginx + Let's Encrypt 做 TLS 反代，
Route 53 解析域名。前端见前端仓库的 `DEPLOY.md`（S3 + CloudFront）。

```
用户 → Route53 →  api.your-domain.com  → nginx(TLS) → app:8080 ┐
                  www.your-domain.com  → CloudFront → S3(前端)  │
                                              EC2 ──IAM Role──→ S3 / SQS
                                              S3 ──对象创建事件──→ SQS → app
                                              app 日志 → CloudWatch（后续可接 OpenSearch）
```

> 💰 成本（us-east-1，约）：t3.large ≈ $60/月（用 Spot/Savings 或「只在面试期开机」更省）、
> S3/SQS 基本免费额度内、CloudFront 首年 1TB 免费、Route53 $0.5/月 + 域名 ~$12/年、
> ACM 免费。**OpenSearch 较贵，建议就业后再上，现用 CloudWatch。**

---

## 1. 前置
- 一个域名（Route 53 或任意注册商）
- AWS 账号；已有 S3 桶 `honghu-ai-document-upload`、`honghu-ai-avatar` 与 SQS 队列 `honghu-ai-document-upload-received`

## 2. 创建 IAM Role（给 EC2，免密钥访问 S3/SQS）
创建 Role（信任实体 = EC2），附加如下最小权限策略（替换 `ACCOUNT_ID`）：

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": [
        "arn:aws:s3:::honghu-ai-document-upload/*",
        "arn:aws:s3:::honghu-ai-avatar/*"
      ] },
    { "Effect": "Allow", "Action": ["s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::honghu-ai-document-upload",
        "arn:aws:s3:::honghu-ai-avatar"
      ] },
    { "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:us-east-1:ACCOUNT_ID:honghu-ai-document-upload-received" }
  ]
}
```
> 应用的 `AwsConfig` 在未配置 profile/静态 key 时使用 `DefaultCredentialsProvider`，会自动读取此实例角色。所以 `.env` 里 **不要填 AWS key，`AWS_PROFILE` 留空** 即可。

## 3. 配置 S3 → SQS 事件
在 `honghu-ai-document-upload` 桶 → 属性 → 事件通知：事件类型 `s3:ObjectCreated:*`，目标选队列 `honghu-ai-document-upload-received`（队列访问策略需允许该桶 `SendMessage`）。这样上传完成会触发 `@SqsListener` 的 RAG 摄取链路。

## 4. 启动 EC2 并装 Docker
- 实例：**t3.large（8GB）**、Amazon Linux 2023、挂上面的 IAM Role、分配 **Elastic IP**
- 安全组：放行 22（你的 IP）、80、443
```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user      # 重新登录生效
# 安装 docker compose 插件
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
```

## 5. 部署应用
```bash
sudo mkdir -p /opt/honghu-ai && sudo chown ec2-user /opt/honghu-ai
cd /opt/honghu-ai
git clone https://github.com/shilaoban666/Honghu-ai-assigmemt.git .
cp .env.prod.example .env && vi .env          # 填 DB_PASSWORD / REDIS_PASSWORD / DEEPSEEK_CLOUD_API_KEY / APP_SKILL_SECRET_KEY / 域名
# APP_SKILL_SECRET_KEY：后台保存的模型 Provider API Key 用它做 AES-GCM 加密，
# 生产必须设为稳定强随机值；更改/丢失会导致已存 Key 无法解密。
# 登录 GHCR（用一个有 read:packages 权限的 PAT），以便拉取镜像
echo "<GHCR_READ_PAT>" | docker login ghcr.io -u shilaoban666 --password-stdin
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

## 6. nginx + TLS 反代
```nginx
# /etc/nginx/conf.d/honghu.conf
server {
  listen 80;
  server_name api.your-domain.com;
  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # SSE 流式聊天：关闭缓冲
    proxy_buffering off;
    proxy_read_timeout 600s;
  }
}
```
```bash
sudo dnf install -y nginx certbot python3-certbot-nginx
sudo systemctl enable --now nginx
sudo certbot --nginx -d api.your-domain.com      # 自动配 443 + 续期
```

## 7. DNS（Route 53）
- `api.your-domain.com` → A 记录 → EC2 Elastic IP
- `www.your-domain.com` → 别名 → 前端 CloudFront（见前端仓库 DEPLOY.md）

## 8. CORS（需一处小改动）⚠️
当前 `config/WebConfig.java` 写死 `allowedOriginPatterns("*")`。生产应改为读环境变量并收紧到真实域名，例如：
```java
@Value("${app.cors.allowed-origins:*}")
private String allowedOrigins;       // 逗号分隔
// addCorsMappings 中：
registry.addMapping("/**")
        .allowedOriginPatterns(allowedOrigins.split(","))
        .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
        .allowedHeaders("*").allowCredentials(true).maxAge(3600);
```
随后 `.env` 里设 `APP_CORS_ALLOWED_ORIGINS=https://www.your-domain.com`。

## 9. 日志（现在 → 将来）
- **现在（免费够用）**：用 CloudWatch agent 或 docker `awslogs` 日志驱动把容器日志送 CloudWatch Logs。
- **将来上 ELK**：加 `logback-spring.xml` 输出 JSON → Fluent Bit 采集 → **AWS OpenSearch Service**。建议有预算（或就业）后再开。

## 10. GitHub Secrets（CI/CD 用）
仓库 Settings → Secrets and variables → Actions：

| Secret | 用途 |
|:---|:---|
| `EC2_HOST` | EC2 公网 IP / 域名 |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | 部署用 SSH 私钥 |
| `GHCR_PAT` | EC2 拉 GHCR 镜像的只读 PAT（`read:packages`） |

## 11. 发布与一键部署
```bash
git tag v1.0.0 && git push origin v1.0.0    # 触发 release.yml：构建 jar + 推 GHCR 镜像 + 建 Release
```
然后在 GitHub → Actions → **Deploy** → Run workflow，输入 tag（如 `v1.0.0`）即**一键部署**到 EC2。
