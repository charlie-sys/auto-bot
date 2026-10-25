# 云服务器部署（v2 多用户）

部署结构：Android App / 网页 → HTTPS 网关 Caddy → 账号 API 容器 → 每位用户配置的模型。

云端只部署后端，不需要 GPU 或 Android SDK。须使用 v2 APK，在登录页填写云端地址并用个人账号登录；注册后由超级管理员审核。账号、密钥加密配置、会话、配额和用量存放在持久数据卷中。

## 1. 准备服务器、域名和 Docker

以下命令以 Ubuntu 24.04 LTS、可使用 sudo 的 SSH 用户为例。

- 准备一台有公网 IP 的 Linux 云服务器。
- 准备子域名，例如 `questions.example.com`，将 DNS A 记录指向服务器公网 IPv4。若配置了 AAAA，确保也指向这台服务器的可达 IPv6。
- 在云厂商安全组和服务器防火墙放行 TCP 80、443；SSH 端口仅对自己的来源地址放行。不要开放 8080，也不需要开放 Docker 管理端口。
- 按 [Docker 官方 Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/)安装 Docker Engine 和 Compose 插件。使用预装 Docker 的云镜像也可以。确认以下命令可用：

```bash
sudo docker version
sudo docker compose version
```

服务器需要能访问容器镜像仓库、Python 依赖源和用户选择的模型接口。若已有服务占用 80/443，请接入已有的 HTTPS 反向代理，不要直接覆盖现有网关。

## 2. 上传后端文件

在服务器 SSH 终端执行（首次创建目录）：

```bash
sudo install -d -m 700 -o "$(id -un)" -g "$(id -gn)" /opt/auto-bot
```

在本机 PowerShell 执行，替换 `ubuntu` 和 `服务器公网IP`：

```powershell
Set-Location D:\codex-workdir\auto-bot
scp compose.cloud.yaml Caddyfile.cloud .env.cloud.example outputs/auto-bot-cloud.zip ubuntu@服务器公网IP:/opt/auto-bot/
```

不要上传本机 `.env`、APK、`.tools`、`outputs`、手机截图或历史照片。若原来没有云端目录，先完成上一条目录创建命令。

将 `auto-bot-cloud.zip` 解压到 `/opt/auto-bot`。该包按文件白名单生成，仅包含后端源码、云端配置模板及说明，不包含 `server/data`、实际密钥或手机数据；不要直接打包整个工作目录。

## 3. 配置域名

回到服务器 SSH 终端：

```bash
cd /opt/auto-bot
umask 077
cp .env.cloud.example .env.cloud
nano .env.cloud
chmod 600 .env.cloud
```

填写以下内容：

```dotenv
DOMAIN=questions.example.com
```

`DOMAIN` 只写域名。v2 不需要全局模型 Key 或共享访问令牌；每位用户在登录后的个人设置中配置自己的模型。

这一步仅首次部署时复制模板。更新时保留已有 `.env.cloud` 和 Caddy 数据卷。

## 4. 一条命令启动

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml up -d --build
```

域名解析生效、80/443 可达后，Caddy 会自动申请和续期 HTTPS 证书。参见 [Caddy 自动 HTTPS 说明](https://caddyserver.com/docs/automatic-https)。API 的 8080 端口只在 Docker 容器网络中开放，没有发布到服务器公网端口。

检查状态：

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml ps
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml logs --tail=60 gateway api
curl --fail https://questions.example.com/health
```

健康检查应包含 `{"ok":true,"accounts_ready":true,"auth_mode":"accounts"}`。初始管理员通过本机命令读取，输出含随机临时密码，仅在自己的私密终端查看：

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml exec -T api python manage.py bootstrap-info
```

浏览器访问 `https://questions.example.com/admin`，用初始 `admin` 登录并修改密码，然后审核用户申请。模型有效性需在个人设置中测试。

## 5. 手机连接

打开 v2 App 登录页：

- 服务地址：`https://questions.example.com`，不加 `/v1/solve-batch`。
- 使用该后端上的个人账号和密码登录。

保存后关闭电脑上的本地后端或拔掉 USB，用手机移动网络拍摄一道公开题目，确认能完成解答。之后电脑无需开机，手机可通过互联网直接使用云端服务。

## 更新与停止

上传新版本后重新执行启动命令。保留 `.env.cloud` 与 `reader_data` 数据卷；不要覆盖加密密钥。

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml up -d --build
```

停止服务（保留账号与证书数据）：

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml down
```

常规更新、停止都不要加 `-v`。`reader_data` 内的 SQLite 数据库与 `encryption.key` 必须一起备份；可用同一 Compose 命令前缀执行 `python manage.py backup` 后导出受限备份。更多账号与备份规则见 `ACCOUNTS.md`。

## 使用前了解

HTTPS 保护手机到服务器的传输，不会消除模型服务商接收题目图片、本地历史保存、调试版 APK 等已有隐私风险。避免拍摄敏感信息，并在 App 中关闭不需要的照片保存。此部署包没有建立公开多用户账户系统，也没有更改模型服务商的数据处理政策。
