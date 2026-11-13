package com.example.aftersales.platform;

import com.example.aftersales.common.ApiResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import(ServiceExceptionHandler.class)
public class ServiceSecurityConfiguration {
    @Bean
    SecurityFilterChain serviceSecurity(HttpSecurity http, ObjectMapper json,
            @Value("${app.services.auth-url}") String authUrl,
            @Value("${app.services.auth-token}") String serviceToken) throws Exception {
        http
            // 写请求已经在 RemoteIdentityFilter 中转交认证服务检查 Session + CSRF。
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
            .addFilterBefore(new RemoteIdentityFilter(authUrl, serviceToken, json), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/knowledge/**", "/api/v1/evaluations/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                response.setStatus(403);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getWriter(), ApiResponses.error("FORBIDDEN", "没有访问权限", request));
            }));
        return http.build();
    }
}
