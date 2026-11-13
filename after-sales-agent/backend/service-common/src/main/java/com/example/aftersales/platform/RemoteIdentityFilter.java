package com.example.aftersales.platform;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 业务服务不读取 Redis Session；通过有超时限制的内部 HTTP 请求核验会话。 */
public final class RemoteIdentityFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final URI endpoint;
    private final String serviceToken;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    public RemoteIdentityFilter(String authUrl, String serviceToken, ObjectMapper json) {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalArgumentException("SERVICE_AUTH_TOKEN must contain at least 32 characters");
        }
        endpoint = URI.create(authUrl + "/internal/auth/identity");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("AUTH_SERVICE_URL must be an HTTP(S) origin");
        }
        this.serviceToken = serviceToken;
        this.json = json;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/actuator/health".equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws IOException, ServletException {
        Caller caller;
        try {
            var check = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("X-Service-Token", serviceToken);
            String cookie = request.getHeader("Cookie");
            if (cookie != null) check.header("Cookie", cookie);
            // 安全方法用 GET；任何写方法用 POST，交由认证服务的真实 CSRF 过滤器校验。
            if (SAFE.contains(request.getMethod())) {
                check.GET();
            } else {
                check.POST(HttpRequest.BodyPublishers.noBody());
                String csrf = request.getHeader("X-CSRF-TOKEN");
                if (csrf != null) check.header("X-CSRF-TOKEN", csrf);
            }
            var result = client.send(check.build(), HttpResponse.BodyHandlers.ofString());
            result.headers().allValues("Set-Cookie").forEach(value -> response.addHeader("Set-Cookie", value));
            if (result.statusCode() != 200) {
                int status = result.statusCode() == 401 ? 401 : result.statusCode() == 403 ? 403 : 503;
                error(request, response, status);
                return;
            }
            caller = json.readValue(result.body(), Caller.class);
            if (caller.id() == null || caller.username() == null
                    || !Set.of("CUSTOMER", "ADMIN").contains(caller.role())) {
                error(request, response, 503);
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error(request, response, 503);
            return;
        } catch (IOException | IllegalArgumentException | NullPointerException exception) {
            error(request, response, 503);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.role()))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void error(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String code = status == 401 ? "UNAUTHENTICATED" : status == 403 ? "FORBIDDEN" : "AUTH_SERVICE_UNAVAILABLE";
        json.writeValue(response.getWriter(), ApiResponses.error(code,
                status == 503 ? "认证服务暂不可用" : "身份或安全令牌校验失败", request));
    }
}
