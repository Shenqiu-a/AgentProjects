## 前置知识

@Bean 注解

Spring 容器

## Spring Security

### 1.需要的 Security Config (基础)

    Spring Boot 3.x(基于Spring Security 6.x)，通常通过声明 SecurityFilterChain Bean 来替代旧版的 WebSecurityConfigurerAdapter

```java
@Configuration
public class SecurityConfig {
    // 需要一个 passwordEncoder
    @Bean
    PasswordEncoder passwordEncoder () {
        return new BCryptPassowrdEncoder(10);
    }

    // 需要一个用来执行账号密码认证的认证管理器
    // user 为一个实现了UserDetailsService接口的类，要重写loadUserByUsername方法
    // 由特定的 Filter 触发
    @Bean
    AuthenticationManager authenticationManager (DatabaseUserDetailsService user, PasswordEncoder passwordEncoder) {
        // DaoAuthenticationProvider 本身就是为了 “用户查询服务 + 密码校验器” 这种认证方式设计的
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    // 登录后如何记住用户？
    @Bean
    SecurityContextRepostitory securityContextRepository () {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain (HttpSecurity http,
                                                SecurityContextRepository contextRepository) throw Exception {
        http
            .securityContext(context -> context
                .sercurityContextRepository(contextRepository)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**", "/login").permitAll() // 允许公开访问的路径
                .requestMatchers("/admin/**").hasRole("ADMIN") // 需要权限的路径
                .anyRequest().authenticated() // 其他请求需要认证
            )
            .formLogin(form -> form
                .defaultSuccessUrl("/home", true)
                .permitAll()
            )
            // 退出时也可做很多事情 清理身份，使 SessionID 失效等
            .logout(logout -> logout
                .permitAll()
            );
    }
}
```

**大致流程：**

    定义一个 PasswordEncoder，通过交给 Spring 自动管理，由过滤器链拦截，触发 AuthenticationManager 进行账号密码的验证，此时会自动装配前面的 PasswordEncoder 和 实现了 UserDetialService 接口的类(需要使用 @Service 注解)，将这两个装配进 DaoAuthenticationProvider，再用 ProviderManager 包装这个 provider，交给 Spring 管理。

## Redis 滑动窗口实现登录限流

接收到请求，检查请求频率，超过限制则拒绝本次请求，防止有人不停尝试密码，导致反复查询数据库，消耗密码校验所需的CPU，也会增加密码被猜中的风险。

这边限制了三个维度

- ip：同一个IP，所有账号，不断登录请求

- account-ip：同一个IP，同一个账号，不断登录请求

- account：同一个账号，在所有IP，不断登录请求

具体配置参数放在了配置文件中

```yml
app:
    security:
        login-rate-limit:
            namespace: after-sales:microservices:login-limit
            ip:
                limit: 30
                window-seconds: 60
            account-ip:
                limit: 5
                window-seconds: 300
            account:
                limit: 20
                window-seconds: 300
```

在 LoginRateLimitProperties 中，将外部参数绑定为 Java 对象

```java
@Validated
@ConfigurationProperties(prefix = "app.security.login-rate-limit") // 负责将外部参数绑定为 Java 对象
public record LoginRateLimitProperties(
    @NotBlank String nameSpace,
    @NotNull @Valid Rule ip,
    @NotNull @Valid Rule accountIp,
    @NotNull @Valid Rule account
    ) {
    public record Rule(
        @Min(1) @Max(10000) int limit,
        @Min(1) @Max(86400) int windowSeconds
    ){}
}
```

Lua脚本

```lua
-- KEYS[1]: 当前维度的 key
-- ARGV[1]: 窗口毫秒数; ARGV[2]: 允许次数; ARGV[3]: 请求 UUID
-- 返回 0 表示允许；正整数表示至少需要等待的毫秒数
local key = KEYS[1]
local windowMs = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- 统一使用 Redis 时间，避免应用实例之间的时钟差异
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- 删除窗口之外的旧记录
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)

-- ZCARD 返回有序集合中的成员数量
local count = redis.call('ZCARD', key)
if count >= limit then
    -- 配置下调后 count 可能大于 limit，需要等到足够多的记录过期
    local index = count - limit
    -- ZRANGE 在这里按分数从小到大排序，用下标取成员  这边取的是最早的请求
    local oldest = redis.call('ZRANGE', key, index, index, 'WITHSCORES')
    -- oldest[2] 表示返回结果中的“分数”，不是第二条请求
    return math.max(1, tonumber(oldest[2]) + windowMs - now)
end

redis.call('ZADD', key, now, ARGV[3])
redis.call('PEXPIRE', key, windowMs)
return 0

```

验证逻辑

```java
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
```
