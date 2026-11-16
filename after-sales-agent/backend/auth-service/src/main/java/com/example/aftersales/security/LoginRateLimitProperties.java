package com.example.aftersales.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
    application.yml
        ↓ 提供参数
    LoginRateLimitProperties
        ↓ 提供限流规则
    LoginRateLimiter
        ↓ 执行检查
    Redis Lua 脚本
 */

/** 同一环境的所有实例必须使用相同的命名空间和限流规则。 */
@Validated
@ConfigurationProperties(prefix = "app.security.login-rate-limit") // 负责把外部参数绑定成 Java 对象
public record LoginRateLimitProperties(
        @NotBlank String namespace,
        @NotNull @Valid Rule ip,
        @NotNull @Valid Rule accountIp,
        @NotNull @Valid Rule account) {

    public record Rule(
            @Min(1) @Max(10000) int limit,
            @Min(1) @Max(86400) int windowSeconds) {
    }
}
