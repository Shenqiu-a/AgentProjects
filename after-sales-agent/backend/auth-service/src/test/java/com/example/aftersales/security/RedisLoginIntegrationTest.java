package com.example.aftersales.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接显式指定的测试 Redis（建议与 Compose 一致使用 Redis 7.4）。
 * 不使用开发会话命名空间、不执行 FLUSHDB/FLUSHALL；仅清理本次 UUID 前缀。
 */
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisLoginIntegrationTest {

    private LettuceConnectionFactory firstConnection;
    private LettuceConnectionFactory secondConnection;
    private StringRedisTemplate firstRedis;
    private StringRedisTemplate secondRedis;
    private String namespace;

    @BeforeEach
    void setUp() {
        namespace = "after-sales:test:" + UUID.randomUUID();
        firstConnection = connection();
        secondConnection = connection();
        firstRedis = new StringRedisTemplate(firstConnection);
        secondRedis = new StringRedisTemplate(secondConnection);
    }

    @AfterEach
    void cleanUp() {
        try {
            if (firstRedis != null) {
                var keys = firstRedis.keys(namespace + ":*");
                if (keys != null && !keys.isEmpty()) {
                    firstRedis.delete(keys);
                }
            }
        } finally {
            if (firstConnection != null) {
                firstConnection.destroy();
            }
            if (secondConnection != null) {
                secondConnection.destroy();
            }
        }
    }

    @Test
    void concurrentRequestsAcrossInstancesShareOneQuota() throws Exception {
        var rules = new LoginRateLimitProperties(namespace + ":login",
                new LoginRateLimitProperties.Rule(1000, 60),
                new LoginRateLimitProperties.Rule(5, 60),
                new LoginRateLimitProperties.Rule(1000, 60));
        var first = new LoginRateLimiter(firstRedis, rules);
        var second = new LoginRateLimiter(secondRedis, rules);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 60; i++) {
            var instance = i % 2 == 0 ? first : second;
            tasks.add(() -> {
                try {
                    instance.check("192.0.2.1", "alice");
                    return true;
                } catch (LoginRateLimitException exception) {
                    assertThat(exception.retryAfterSeconds()).isPositive();
                    return false;
                }
            });
        }

        try (var executor = Executors.newFixedThreadPool(12)) {
            var results = executor.invokeAll(tasks, 15, TimeUnit.SECONDS);
            long admitted = 0;
            for (var result : results) {
                if (result.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(5);
        }
    }

    @Test
    void switchingIpStillUsesTheSameAccountQuota() {
        var rules = new LoginRateLimitProperties(namespace + ":login",
                new LoginRateLimitProperties.Rule(100, 60),
                new LoginRateLimitProperties.Rule(100, 60),
                new LoginRateLimitProperties.Rule(2, 60));
        var first = new LoginRateLimiter(firstRedis, rules);
        var second = new LoginRateLimiter(secondRedis, rules);

        first.check("192.0.2.1", "alice");
        second.check("192.0.2.2", "alice");
        assertThatThrownBy(() -> first.check("192.0.2.3", "alice"))
                .isInstanceOf(LoginRateLimitException.class);
        assertThatCode(() -> second.check("192.0.2.3", "bob")).doesNotThrowAnyException();
    }

    @Test
    void quotaRecoversWhenTheWindowExpires() throws InterruptedException {
        var rule = new LoginRateLimitProperties.Rule(1, 1);
        var rules = new LoginRateLimitProperties(namespace + ":login", rule, rule, rule);
        var first = new LoginRateLimiter(firstRedis, rules);
        var second = new LoginRateLimiter(secondRedis, rules);

        first.check("192.0.2.1", "alice");
        assertThatThrownBy(() -> second.check("192.0.2.1", "alice"))
                .isInstanceOf(LoginRateLimitException.class);
        Thread.sleep(1100);
        assertThatCode(() -> second.check("192.0.2.1", "alice")).doesNotThrowAnyException();
    }

    @Test
    void sessionIdentityAndCsrfAreSharedAndCanBeInvalidatedByAnotherInstance() {
        assertSharedSession(sessionRepository(firstConnection), sessionRepository(secondConnection));
    }

    // RedisSessionRepository 返回的具体 Session 类型为包可见，通过公开接口的类型参数操作。
    private <S extends Session> void assertSharedSession(SessionRepository<S> first, SessionRepository<S> second) {
        S session = first.createSession();
        var user = User.withUsername("alice").password("not-a-real-hash").roles("CUSTOMER").build();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute("csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString()));
        first.save(session);

        var restored = second.findById(session.getId());
        assertThat(restored).isNotNull();
        SecurityContext restoredContext = restored.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(restoredContext.getAuthentication().getName()).isEqualTo("alice");
        CsrfToken csrf = restored.getAttribute("csrf");
        assertThat(csrf.getToken()).isEqualTo(((CsrfToken) session.getAttribute("csrf")).getToken());

        second.deleteById(session.getId());
        assertThat(first.findById(session.getId())).isNull();
    }

    private RedisSessionRepository sessionRepository(LettuceConnectionFactory connection) {
        var redis = new RedisTemplate<String, Object>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(RedisSerializer.string());
        redis.setValueSerializer(RedisSerializer.java());
        redis.setHashKeySerializer(RedisSerializer.string());
        redis.setHashValueSerializer(RedisSerializer.java());
        redis.afterPropertiesSet();
        var repository = new RedisSessionRepository(redis);
        repository.setRedisKeyNamespace(namespace + ":session");
        return repository;
    }

    private static LettuceConnectionFactory connection() {
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
        var configuration = new RedisStandaloneConfiguration(System.getenv("REDIS_TEST_HOST"), port);
        String username = System.getenv("REDIS_TEST_USERNAME");
        String password = System.getenv("REDIS_TEST_PASSWORD");
        if (username != null && !username.isBlank()) {
            configuration.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            configuration.setPassword(password);
        }
        var connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        connection.start();
        return connection;
    }
}
