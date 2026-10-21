# 演示环境部署

> **当前未启用。** 两个仓库的 deploy workflow 只支持手动触发，服务器尚未初始化。
> 启用时按本文完成服务器初始化、`.env` 与 GitHub Secret，再到 Actions 手动运行。

一台云服务器（大陆）跑两套 compose，公网只暴露 80/443，Caddy 做入口与 Basic Auth。有域名后自动启用 HTTPS；暂时没有域名时用 `http://服务器IP/`。

```
GitHub Actions（两个仓库各一份，流程相同）
  ├─ mvn test
  └─ 构建镜像 ──▶ ghcr.io/<owner>/…

服务器只保留运行文件，不 clone 任何仓库：
  /opt/idtp/agent-platform/   docker-compose.yml  deploy/Caddyfile  knowledge/  .env
  /opt/idtp/business-systems/ docker-compose.yml  expense/  repair/
```

平台镜像与 `meeting-booking` 镜像都由 Runner 构建、推 GHCR，服务器只 `pull`；两个 WireMock stub 没有业务代码，直接把 `expense/`、`repair/` 目录同步过去。

## 1. 服务器初始化

安全组入站只开 **22（限定来源 IP）和 80**；有域名后再加 443。

> Docker 发布端口会直接写 iptables，**绕过 ufw/firewalld**；网络边界必须落在云安全组。

```bash
curl -fsSL https://get.docker.com | sh -s -- --mirror Aliyun
apt install -y rsync
docker compose version

mkdir -p /opt/idtp/agent-platform/deploy /opt/idtp/agent-platform/knowledge
mkdir -p /opt/idtp/business-systems
```

不需要 git，也不需要 Java / Maven。

## 2. 平台 `.env`

`/opt/idtp/agent-platform/.env`（`chmod 600`）：

```dotenv
LLM_API_KEY=...
LLM_MODEL=deepseek-flash
LLM_BASE_URL=https://api.deepseek.com
JWT_SECRET=...                      # openssl rand -base64 48
EMBEDDING_API_KEY=...               # 留空则不启用知识库
DEMO_DOMAIN=                        # 没有域名就留空，Caddy 用 :80
BASIC_AUTH_USER=demo
BASIC_AUTH_HASH='$2a$14$...'        # 单引号！bcrypt 里的 $ 会被 compose 当作变量插值
APP_PORT_BINDING=127.0.0.1:8000:8000
MYSQL_PORT_BINDING=127.0.0.1:3307:3306
PGVECTOR_PORT_BINDING=127.0.0.1:5433:5432
```

生成 Basic Auth 哈希：

```bash
docker run --rm caddy:2-alpine caddy hash-password --plaintext '换成强密码'
```

> 有域名后把 `DEMO_DOMAIN` 改成域名，再在服务器执行一次 `cd /opt/idtp/agent-platform && docker compose --profile prod up -d caddy`，Caddy 会自动申请证书并切到 HTTPS。

## 3. GHCR 可见性

两个仓库首次推送镜像后，到 GitHub → Packages 把两个包设为 **public**：

- `agent-platform`
- `business-systems/meeting-booking`

否则服务器 `docker pull` 会 401。镜像里只有代码与 jar，不含 `.env`。

> 大陆服务器拉 `ghcr.io` 可能较慢；实测经常超时的话，把两个 workflow 里的镜像地址换成阿里云 ACR 并加登录步骤。

## 4. GitHub Secret

**两个仓库都要配**（Settings → Secrets and variables → Actions）：

| 名称 | 用途 |
|---|---|
| `SSH_HOST` / `SSH_USER` / `SSH_PORT` | 服务器登录（端口可省略，默认 22） |
| `SSH_KEY` | 部署专用私钥（无 passphrase） |

```bash
ssh-keygen -t ed25519 -C "github-actions-idtp" -f ~/.ssh/idtp_deploy -N ""
# 公钥追加到服务器 ~/.ssh/authorized_keys，私钥内容填入 SSH_KEY
```

## 5. 首次部署

先部署业务系统，再部署平台（平台启动时拉三个系统的契约，业务系统不在会等到下一轮对账）：

1. `business-systems` 推 `main`：测试 → 构建 booking 镜像 → 同步 stub 目录与 compose → 拉镜像启动。
2. `agent-platform` 推 `main`：测试 → 构建平台镜像 → 同步 compose/Caddyfile/knowledge → 拉镜像启动 → 探活。

打开 `http://服务器IP/`（或域名）应弹出 Basic Auth；登录后发一句话，SSE 事件应逐条出现，而不是等回答结束一次性刷出。入口有问题时看 `docker compose --profile prod logs caddy`。

## 6. 更新与回滚

- 平台：推送 `main` 即自动发布，服务器按新的 sha tag 拉镜像。
- 业务系统：同样推 `main`。stub 目录和 compose 由 rsync 同步（`--delete`，仓库里删掉的文件不会残留）。
- 回滚：用上一个 commit 的 sha 作为 tag 启动对应服务。

```bash
cd /opt/idtp/agent-platform
APP_IMAGE=ghcr.io/<owner>/agent-platform:<上一个 sha> docker compose --profile prod up -d

cd /opt/idtp/business-systems
BOOKING_IMAGE=ghcr.io/<owner>/business-systems/meeting-booking:<上一个 sha> docker compose up -d
```

- 备份（演示环境可加 cron）：`docker exec agent-platform-mysql mysqldump -uroot -proot agent_platform | gzip > backup.sql.gz`

## 7. 首次部署后改密码

`admin/admin123`、`staff01/staff123` 是公开的。项目没有改密接口，直接更新哈希：

```bash
python3 - <<'PY'
import base64, hashlib, os
password = "换成强密码"
salt = os.urandom(16)
dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 210000, 32)
print(f"pbkdf2$210000${base64.b64encode(salt).decode()}${base64.b64encode(dk).decode()}")
PY
```

```bash
docker exec -i agent-platform-mysql mysql -uroot -proot agent_platform \
  -e "UPDATE app_user SET password_hash='<上面的输出>' WHERE username='admin';"
```

## 8. 边界

- 这是**单实例演示环境**：没有多副本、共享确认状态、工具级限流与审计配额。
- Basic Auth 挡的是陌生访问，不替代平台自身的 RBAC；公开演示前仍要改种子密码，并在 LLM 供应商侧设置额度上限。
- 没有域名时 Basic Auth 走明文 HTTP，只适合临时验证；有域名后尽快切 HTTPS。
- 两个仓库各自独立发布；平台先于业务系统启动时，契约对账会在下一轮自动补齐。

## 9. 设计取舍

| 选择 | 原因 |
|---|---|
| Runner 构建镜像 → GHCR，服务器只 `pull` | 生产机不装 JDK/Maven；同一 commit 产出同一镜像；回滚切 sha tag，不重新编译；构建失败不占线上资源 |
| 服务器不 clone，改用 rsync | compose/Caddyfile/knowledge/stub 是运行时文件，不是要构建的源码；`--delete` 保证仓库里删掉的文档和 stub 不残留 |
| 业务系统也走同一套 | 避免"平台拉镜像、业务系统在服务器编译"两套心智；booking 的 Maven 构建也移出生产机 |
| 手动触发 | 演示环境不需要每次 push 都发布；启用自动发布时加回 `push: branches: [main]` |

代价：依赖 GHCR（大陆拉取可能慢，可换 ACR）；两个镜像包要设为 public；两个仓库各配一次 SSH Secret。
