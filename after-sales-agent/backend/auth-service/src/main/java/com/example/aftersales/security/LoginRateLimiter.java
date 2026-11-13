package com.example.aftersales.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Redis 滑动窗口登录限流，在查询用户和 BCrypt 校验前执行。 */
@Service
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final DefaultRedisScript<Long> SCRIPT = createScript();

    private final StringRedisTemplate redis;
    private final LoginRateLimitProperties properties;

    public LoginRateLimiter(StringRedisTemplate redis, LoginRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(String ip, String username) {
        if (ip == null || ip.isBlank()) {
            throw new InternalAuthenticationServiceException("Client address is unavailable");
        }

        // 不单独 trim/转小写，保持当前数据库用户名查询的匹配语义。
        // 逐项预占额度；后续规则拒绝时不回退前面已经占用的额度。
        checkRule("ip", sha256(ip), properties.ip());
        checkRule("account-ip", sha256(ip + "\n" + username), properties.accountIp());
        checkRule("account", sha256(username), properties.account());
    }

    private void checkRule(String dimension, String identity, LoginRateLimitProperties.Rule rule) {
        String key = properties.namespace() + ":" + dimension + ":" + identity;
        Long retryMs;
        try {
            // 每次脚本只访问一个 key，避免 Redis Cluster 跨槽操作。
            retryMs = redis.execute(SCRIPT, List.of(key),
                    Long.toString(rule.windowSeconds() * 1000L),
                    Integer.toString(rule.limit()), UUID.randomUUID().toString());
        } catch (DataAccessException exception) {
            log.error("Redis 登录限流执行失败，dimension={}", dimension, exception);
            // 无法确认额度时拒绝认证，复用现有的 503 异常响应。
            throw new InternalAuthenticationServiceException("Login rate limiter unavailable", exception);
        }

        if (retryMs == null || retryMs < 0) {
            log.error("Redis 登录限流返回异常，dimension={}", dimension);
            throw new InternalAuthenticationServiceException("Unexpected login rate limiter result");
        }
        if (retryMs > 0) {
            // 不输出账号、密码、Cookie 或 CSRF token。
            log.debug("登录请求触发限流，dimension={}", dimension);
            throw new LoginRateLimitException((retryMs + 999L) / 1000L);
        }
    }

    private static DefaultRedisScript<Long> createScript() {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("redis/login-rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
