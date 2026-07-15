# RCGallery 百度 OAuth 后端

该服务只负责百度 OAuth 登录、一次性授权码交换和 Token 刷新。百度 Secret Key 仅存在于服务器环境变量中。

## 部署

1. 将 `.env.example` 复制为 `.env`。
2. 填写域名、百度 App Key、Secret Key 和随机签名密钥。
3. 将域名 DNS 的 A/AAAA 记录指向服务器。
4. 在百度开放平台把授权回调地址配置为：

   `https://你的域名/oauth/baidu/callback`

5. 启动：

   ```bash
   docker compose up -d --build
   ```

Caddy 会自动申请和续期 HTTPS 证书。

## Android 配置

在项目根目录 `local.properties` 中加入：

```properties
BAIDU_AUTH_BACKEND_URL=https://你的域名
```

重新打包后，“百度账号登录”按钮即可点击。

## 环境变量

- `BAIDU_APP_KEY`：百度开放平台 API Key。
- `BAIDU_SECRET_KEY`：仅保存在服务端，禁止提交 Git。
- `BAIDU_REDIRECT_URI`：必须与百度开放平台登记值完全一致。
- `STATE_SIGNING_SECRET`：至少 32 个字符，建议使用 32 字节随机值。
- `BAIDU_SCOPE`：默认 `basic,netdisk`。

## 安全行为

- OAuth state 使用 HMAC-SHA256 签名并在 10 分钟后失效。
- App 回调固定为 `rcgallery://baidu/oauth`。
- Token 通过 5 分钟有效、只能消费一次的短码回传。
- 响应禁止缓存，日志不输出 Token、Secret Key 或授权码。
- 当前一次性短码保存在单实例内存中。若部署多个副本，应改用 Redis 的原子 GETDEL 并设置 TTL。
