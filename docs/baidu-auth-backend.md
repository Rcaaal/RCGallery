# 百度网盘授权后端契约

RCGallery 不在 APK 中保存百度 `client_secret`。客户端只配置：

```properties
BAIDU_AUTH_BACKEND_URL=https://your-auth.example.com
```

后端负责持有百度 App Key / Secret Key，并实现以下三个接口。

项目已经提供可直接部署的实现，位置为
[`backend/baidu-auth`](../backend/baidu-auth/README.md)。

## 1. 发起授权

`GET /oauth/baidu/start?state=<random>&app_callback=rcgallery%3A%2F%2Fbaidu%2Foauth`

后端将用户重定向到百度 OAuth 授权页。百度回调后，后端完成 code 交换，生成一次性短码，最后重定向：

`rcgallery://baidu/oauth?code=<one-time-code>&state=<original-state>`

一次性短码应在 5 分钟内失效，且只能消费一次。

## 2. 交换一次性短码

`POST /oauth/baidu/exchange`

```json
{"code":"one-time-code","state":"original-state"}
```

成功响应：

```json
{"access_token":"...","refresh_token":"...","expires_in":2592000}
```

## 3. 刷新令牌

`POST /oauth/baidu/refresh`

```json
{"refresh_token":"..."}
```

响应格式与交换接口相同。后端调用百度刷新接口时使用 Secret Key，客户端永远不接触 Secret Key。
