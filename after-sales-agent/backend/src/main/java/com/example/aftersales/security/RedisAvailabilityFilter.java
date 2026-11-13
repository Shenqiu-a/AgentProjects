package com.example.aftersales.security;

import com.example.aftersales.common.ApiResponses;
import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 在 Spring Session 外层处理 Redis 故障，覆盖 CSRF/会话读取和响应提交阶段。 */
public class RedisAvailabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityFilter.class);
    private static final Set<String> RESET_HEADERS = Set.of(
            "content-length", "content-type", "content-encoding", "content-disposition",
            "transfer-encoding", "set-cookie", "location", "etag", "last-modified");
    private final ObjectMapper json;

    public RedisAvailabilityFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException exception) {
            if (!isRedisFailure(exception) || response.isCommitted()) {
                throw exception;
            }
            log.warn("Redis 请求处理失败，异常类型={}", exception.getClass().getSimpleName());
            // reset 同时清除已选择的 writer/outputStream 和旧正文长度。
            // 保留安全/CORS 响应头，不返回尚未保存成功的 Session Cookie。
            resetForError(response);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getWriter(), ApiResponses.error(
                    "AUTH_SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试", request));
        }
    }

    private static void resetForError(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (!RESET_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, new ArrayList<>(response.getHeaders(name)));
            }
        }
        response.reset();
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    private static boolean isRedisFailure(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof RedisConnectionFailureException
                    || current instanceof RedisSystemException
                    || current instanceof RedisException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
