import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BaiduAuthServer {
    private static final String AUTHORIZE_URL = "https://openapi.baidu.com/oauth/2.0/authorize";
    private static final String TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token";
    private static final String APP_CALLBACK = "rcgallery://baidu/oauth";
    private static final long STATE_TTL_SECONDS = 10 * 60L;
    private static final long CODE_TTL_SECONDS = 5 * 60L;
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final Config config;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingGrant> pendingGrants = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    private BaiduAuthServer(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        BaiduAuthServer app = new BaiduAuthServer(config);
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 32);
        server.setExecutor(Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2)
        ));
        server.createContext("/health", app::health);
        server.createContext("/oauth/baidu/start", app::startAuthorization);
        server.createContext("/oauth/baidu/callback", app::oauthCallback);
        server.createContext("/oauth/baidu/exchange", app::exchangeOneTimeCode);
        server.createContext("/oauth/baidu/refresh", app::refreshToken);
        server.start();
        System.out.println("RCGallery Baidu auth backend listening on :" + config.port);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"message\":\"method not allowed\"}");
            return;
        }
        json(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void startAuthorization(HttpExchange exchange) throws IOException {
        if (!allow(exchange, 30) || !"GET".equals(exchange.getRequestMethod())) return;
        Map<String, String> query = Query.parse(exchange.getRequestURI().getRawQuery());
        String appState = query.getOrDefault("state", "");
        String appCallback = query.getOrDefault("app_callback", "");
        if (!appState.matches("[a-fA-F0-9]{32,128}") || !APP_CALLBACK.equals(appCallback)) {
            json(exchange, 400, "{\"message\":\"invalid state or callback\"}");
            return;
        }
        long expiresAt = Instant.now().getEpochSecond() + STATE_TTL_SECONDS;
        String payload = appState + "\n" + appCallback + "\n" + expiresAt + "\n" + randomToken(18);
        String signedState = base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
        String url = AUTHORIZE_URL
            + "?response_type=code"
            + "&client_id=" + enc(config.appKey)
            + "&redirect_uri=" + enc(config.redirectUri)
            + "&scope=" + enc(config.scope)
            + "&display=mobile"
            + "&state=" + enc(signedState);
        redirect(exchange, url);
    }

    private void oauthCallback(HttpExchange exchange) throws IOException {
        if (!allow(exchange, 60) || !"GET".equals(exchange.getRequestMethod())) return;
        Map<String, String> query = Query.parse(exchange.getRequestURI().getRawQuery());
        SignedState state;
        try {
            state = verifyState(query.getOrDefault("state", ""));
        } catch (IllegalArgumentException error) {
            html(exchange, 400, "授权状态无效或已经过期，请返回 RCGallery 重新登录。");
            return;
        }
        String oauthError = query.get("error");
        if (oauthError != null) {
            redirect(exchange, state.appCallback + "?error=" + enc(oauthError) + "&state=" + enc(state.appState));
            return;
        }
        String code = query.getOrDefault("code", "");
        if (code.isEmpty()) {
            html(exchange, 400, "百度未返回授权码，请重新登录。");
            return;
        }
        try {
            TokenBundle token = requestToken(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "client_id", config.appKey,
                "client_secret", config.secretKey,
                "redirect_uri", config.redirectUri
            ));
            cleanupExpiredGrants();
            String oneTimeCode = randomToken(32);
            pendingGrants.put(oneTimeCode, new PendingGrant(
                state.appState,
                token,
                Instant.now().getEpochSecond() + CODE_TTL_SECONDS
            ));
            redirect(exchange, state.appCallback + "?code=" + enc(oneTimeCode) + "&state=" + enc(state.appState));
        } catch (Exception error) {
            System.err.println("Baidu token exchange failed: " + safeMessage(error));
            html(exchange, 502, "百度授权服务暂时不可用，请稍后重试。");
        }
    }

    private void exchangeOneTimeCode(HttpExchange exchange) throws IOException {
        if (!allow(exchange, 60) || !"POST".equals(exchange.getRequestMethod())) return;
        try {
            String body = readBody(exchange);
            String code = Json.string(body, "code");
            String state = Json.string(body, "state");
            PendingGrant grant = pendingGrants.remove(code);
            if (grant == null || grant.expiresAt < Instant.now().getEpochSecond()
                || !MessageDigest.isEqual(grant.appState.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
                json(exchange, 400, "{\"message\":\"invalid or expired one-time code\"}");
                return;
            }
            tokenJson(exchange, grant.token);
        } catch (IllegalArgumentException error) {
            json(exchange, 400, "{\"message\":\"invalid request\"}");
        }
    }

    private void refreshToken(HttpExchange exchange) throws IOException {
        if (!allow(exchange, 60) || !"POST".equals(exchange.getRequestMethod())) return;
        try {
            String refreshToken = Json.string(readBody(exchange), "refresh_token");
            TokenBundle token = requestToken(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", config.appKey,
                "client_secret", config.secretKey
            ));
            tokenJson(exchange, token);
        } catch (IllegalArgumentException error) {
            json(exchange, 400, "{\"message\":\"invalid refresh request\"}");
        } catch (Exception error) {
            System.err.println("Baidu token refresh failed: " + safeMessage(error));
            json(exchange, 502, "{\"message\":\"baidu token refresh failed\"}");
        }
    }

    private TokenBundle requestToken(Map<String, String> params) throws Exception {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!query.isEmpty()) query.append('&');
            query.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL + "?" + query))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        String body = response.body();
        String accessToken = Json.string(body, "access_token");
        String refreshToken = Json.string(body, "refresh_token");
        long expiresIn = Json.longValue(body, "expires_in", 2_592_000L);
        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IllegalStateException("token response incomplete");
        }
        return new TokenBundle(accessToken, refreshToken, Math.max(expiresIn, 60L));
    }

    private SignedState verifyState(String encoded) {
        String[] parts = encoded.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("state format");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        byte[] expected = Base64.getUrlDecoder().decode(sign(payload));
        byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
        if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("state signature");
        String[] values = payload.split("\\n", -1);
        if (values.length != 4 || !APP_CALLBACK.equals(values[1])) throw new IllegalArgumentException("state payload");
        long expiresAt = Long.parseLong(values[2]);
        if (expiresAt < Instant.now().getEpochSecond()) throw new IllegalArgumentException("state expired");
        return new SignedState(values[0], values[1]);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.stateSigningSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean allow(HttpExchange exchange, int maxPerMinute) throws IOException {
        String method = exchange.getRequestMethod();
        if (!("GET".equals(method) || "POST".equals(method))) {
            json(exchange, 405, "{\"message\":\"method not allowed\"}");
            return false;
        }
        String key = clientIp(exchange) + ":" + exchange.getHttpContext().getPath();
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = rateWindows.compute(key, (ignored, old) ->
            old == null || old.minute != minute ? new RateWindow(minute, 1) : new RateWindow(minute, old.count + 1)
        );
        if (window.count > maxPerMinute) {
            json(exchange, 429, "{\"message\":\"too many requests\"}");
            return false;
        }
        return true;
    }

    private static String clientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
        return new String(body, StandardCharsets.UTF_8);
    }

    private void cleanupExpiredGrants() {
        long now = Instant.now().getEpochSecond();
        pendingGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return base64Url(value);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)(access_token|refresh_token|client_secret)=[^&\\s]+", "$1=[redacted]");
    }

    private static void tokenJson(HttpExchange exchange, TokenBundle token) throws IOException {
        json(exchange, 200, "{\"access_token\":\"" + Json.escape(token.accessToken)
            + "\",\"refresh_token\":\"" + Json.escape(token.refreshToken)
            + "\",\"expires_in\":" + token.expiresIn + "}");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        secureHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void html(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>RCGallery</title>"
            + "<body style='font-family:sans-serif;padding:32px'><h2>RCGallery 百度授权</h2><p>"
            + Json.htmlEscape(message) + "</p></body>").getBytes(StandardCharsets.UTF_8);
        secureHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        secureHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void secureHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
    }

    private record SignedState(String appState, String appCallback) {}
    private record TokenBundle(String accessToken, String refreshToken, long expiresIn) {}
    private record PendingGrant(String appState, TokenBundle token, long expiresAt) {}
    private record RateWindow(long minute, int count) {}

    private record Config(
        int port,
        String appKey,
        String secretKey,
        String redirectUri,
        String scope,
        String stateSigningSecret
    ) {
        static Config fromEnvironment() {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            String appKey = required("BAIDU_APP_KEY");
            String secretKey = required("BAIDU_SECRET_KEY");
            String redirectUri = required("BAIDU_REDIRECT_URI");
            String scope = System.getenv().getOrDefault("BAIDU_SCOPE", "basic,netdisk");
            String signingSecret = required("STATE_SIGNING_SECRET");
            if (!redirectUri.startsWith("https://")) throw new IllegalStateException("BAIDU_REDIRECT_URI must use HTTPS");
            if (signingSecret.length() < 32) throw new IllegalStateException("STATE_SIGNING_SECRET must be at least 32 characters");
            return new Config(port, appKey, secretKey, redirectUri, scope, signingSecret);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
            return value.trim();
        }
    }

    private static final class Query {
        static Map<String, String> parse(String rawQuery) {
            Map<String, String> result = new java.util.HashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) return result;
            for (String pair : rawQuery.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                result.put(key, value);
            }
            return result;
        }
    }

    private static final class Json {
        private static String string(String json, String key) {
            Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
            Matcher matcher = pattern.matcher(json);
            if (!matcher.find()) throw new IllegalArgumentException("missing " + key);
            return unescape(matcher.group(1));
        }

        private static long longValue(String json, String key, long fallback) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(\\d+)").matcher(json);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
        }

        private static String unescape(String value) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '\\') { out.append(c); continue; }
                if (++i >= value.length()) throw new IllegalArgumentException("invalid json escape");
                char e = value.charAt(i);
                switch (e) {
                    case '\"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 >= value.length()) throw new IllegalArgumentException("invalid unicode escape");
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("invalid json escape");
                }
            }
            return out.toString();
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        }

        private static String htmlEscape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
