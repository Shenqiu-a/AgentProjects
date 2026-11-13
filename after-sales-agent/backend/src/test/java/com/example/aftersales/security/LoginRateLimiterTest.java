package com.example.aftersales.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginRateLimiterTest {

    private StringRedisTemplate redis;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        var properties = new LoginRateLimitProperties("test:login",
                new LoginRateLimitProperties.Rule(30, 60),
                new LoginRateLimitProperties.Rule(5, 300),
                new LoginRateLimitProperties.Rule(20, 300));
        limiter = new LoginRateLimiter(redis, properties);
    }

    @Test
    void allowsLoginWhenAllRulesAllow() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString())).thenReturn(0L);

        assertThatCode(() -> limiter.check("192.0.2.1", "alice")).doesNotThrowAnyException();

        verify(redis, times(3)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void stopsAtFirstDeniedRuleAndRoundsRetryUp() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString())).thenReturn(1001L);

        assertThatThrownBy(() -> limiter.check("192.0.2.1", "alice"))
                .isInstanceOfSatisfying(LoginRateLimitException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(2));

        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesLoginWhenRedisIsUnavailable() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("test Redis is unavailable"));

        assertThatThrownBy(() -> limiter.check("192.0.2.1", "alice"))
                .isInstanceOf(InternalAuthenticationServiceException.class);
    }

    @Test
    void refusesLoginWhenRedisReturnsNoDecision() {
        assertThatThrownBy(() -> limiter.check("192.0.2.1", "alice"))
                .isInstanceOf(InternalAuthenticationServiceException.class);
    }

    @Test
    void refusesLoginWhenRedisReturnsAnInvalidDecision() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString(), anyString())).thenReturn(-1L);

        assertThatThrownBy(() -> limiter.check("192.0.2.1", "alice"))
                .isInstanceOf(InternalAuthenticationServiceException.class);
    }

    @Test
    void refusesUnknownClientAddressWithoutCallingRedis() {
        assertThatThrownBy(() -> limiter.check(null, "alice"))
                .isInstanceOf(InternalAuthenticationServiceException.class);
        verifyNoInteractions(redis);
    }
}
