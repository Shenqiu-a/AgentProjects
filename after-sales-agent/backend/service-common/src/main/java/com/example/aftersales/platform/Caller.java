package com.example.aftersales.platform;

import java.security.Principal;
import java.util.UUID;

/** 身份只能由认证服务返回，不能从浏览器提供的用户头中构造。 */
public record Caller(UUID id, String username, String role) implements Principal {
    @Override public String getName() { return username; }
}
