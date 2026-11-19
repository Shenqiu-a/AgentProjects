package com.example.aftersales.agent;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 功能：
 * 作者：yml
 * 日期：2026/11/1811:15
 */

@Configuration(proxyBeanMethods = false)
public class InternalTicketSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain internalTickets(HttpSecurity http, ObjectMapper json,
                                        @Value("${app.services.auth-token}") String serviceToken) throws Exception {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalArgumentException("Invalid SERVICE_AUTH_TOKEN");
        }
        byte[] expected = serviceToken.getBytes(StandardCharsets.UTF_8);
        // 仅添加到此安全链，不声明成 Filter Bean，避免被 Boot 再注册成全局过滤器。
        var tokenFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws IOException, ServletException {
                String token = request.getHeader("X-Service-Token");
                if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    json.writeValue(response.getWriter(), ApiResponses.error(
                            "UNAUTHENTICATED", "服务身份校验失败", request));
                    return;
                }
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "internal-service", null, List.of(new SimpleGrantedAuthority("TICKET_QUERY"))));
                SecurityContextHolder.setContext(context);
                try { chain.doFilter(request, response); }
                finally { SecurityContextHolder.clearContext(); }
            }
        };
        return http.securityMatcher("/internal/tickets/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
                .logout(l -> l.disable()).requestCache(c -> c.disable())
                .addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/internal/tickets/active").hasAuthority("TICKET_QUERY")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    json.writeValue(response.getWriter(), ApiResponses.error("FORBIDDEN", "没有访问权限", request));
                })).build();
    }
}
