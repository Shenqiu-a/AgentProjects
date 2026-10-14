package com.example.aftersales.security;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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



    public record CsrfView(String headerName, String token){}
}
