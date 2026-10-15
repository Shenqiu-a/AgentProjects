package com.example.aftersales.security;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
/**
 * 功能：CSRF、JSON 登录、当前用户三个入口
 * 作者：yml
 * 日期：2026/10/1415:42
 */

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final AppUserRepository users;

    public AuthController(AuthenticationManager authenticationManager,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          SecurityContextRepository contextRepository,
                          AppUserRepository users) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionAuthenticationStrategy;
        this.contextRepository = contextRepository;
        this.users = users;
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken csrf, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return ApiResponses.ok(new CsrfView(csrf.getHeaderName(), csrf.getToken()), request);
    }

    @PostMapping("/login")
    public Map<String, Object> login (@Valid @RequestBody LoginRequest body,
                                      HttpServletRequest request, HttpServletResponse response) {
        try {
            // Bcrypt 密码输入有 72 字节上限，不能静默截断后验证
            if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BadCredentialsException("Invalid credentials");
            }
            var token = UsernamePasswordAuthenticationToken.unauthenticated(body.username, body.password);
            Authentication authenticate = authenticationManager.authenticate(token);
            UserView user = activeUser(authenticate.getName());

            sessionStrategy.onAuthentication(authenticate, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticate);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);

            response.setHeader("Cache-Control", "no-store");
            return ApiResponses.ok(user, request);
        } catch (AuthenticationException e) {
            cleanLogin(request, response);
            throw e;
        }

    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        try {
            if (authentication == null) {
                throw new AuthenticationCredentialsNotFoundException("No Session");
            }
            response.setHeader("Cache-Control", "no-store");
            return ApiResponses.ok(activeUser(authentication.getName()), request);
        } catch (AuthenticationException e) {
            cleanLogin(request, response);
            throw e;
        }
    }

    public record LoginRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password){
        @Override
        public String toString() {
            return "LoginRequest[credentials=hidden]";
        }
    }

    private UserView activeUser(String username) {
        var account = users.findByUserName(username).filter(AppUserRepository.Account::enabled)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Inactive user"));
        return new UserView(account.id(), account.username(), account.role());
    }

    private void cleanLogin(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }

    public record UserView(UUID id, String username, String role){}

    public record CsrfView(String headerName, String token){}
}
