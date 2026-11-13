package com.example.aftersales.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** 仅供服务间调用；网关不代理 /internal。POST 同时接受 Spring Security 的 CSRF 校验。 */
@RestController
public class InternalAuthController {
    private final AppUserRepository users;
    private final byte[] serviceToken;

    public InternalAuthController(AppUserRepository users, @Value("${app.services.auth-token}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("SERVICE_AUTH_TOKEN must contain at least 32 characters");
        }
        this.users = users;
        serviceToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @RequestMapping(path = "/internal/auth/identity", method = {RequestMethod.GET, RequestMethod.POST})
    public Identity identity(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader("X-Service-Token");
        if (supplied == null || !MessageDigest.isEqual(serviceToken, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var user = users.findByUserName(authentication.getName()).filter(AppUserRepository.Account::enabled)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Inactive user"));
        response.setHeader("Cache-Control", "no-store");
        return new Identity(user.id(), user.username(), user.role());
    }

    public record Identity(UUID id, String username, String role) {}
}
