package com.example.aftersales.security;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.util.*;

/**
 * 功能：认证器、密码算法、会话存储、安全过滤器链与登出
 * 作者：yml
 * 日期：2026/10/1415:42
 */

@Configuration  //表示这是一个向 Spring 提供组件定义的配置类
@EnableMethodSecurity // 启用方法级权限检查  启用方法安全后，还需要在具体方法上写权限规则，才会产生对应的方法级限制。
@EnableConfigurationProperties(LoginRateLimitProperties.class) // 注册前面学过的限流参数类，让 Spring 绑定并校验它的配置
public class SecurityConfig {

    // Redis 会话访问出错怎么办？ 自定义过滤器处理
    @Bean
    FilterRegistrationBean<RedisAvailabilityFilter> redisAvailabilityFilter(ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new RedisAvailabilityFilter(json));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    // 密码怎么校验？
    //  BCrypt

    /*
     * PasswordEncode 是接口，表示需要一种“密码编码与校验能力”
     * BCryptPasswordEncoder 是具体实现，其中的 10 为 BCrypt 的工作因子，影响计算成本
     * 方法名 passwordEncoder 也默认是这个 Bean 的名称
     *
     * 其他类需要这个组件的时候，可以通过构造器或者 @Bean 方法参数接收它
     */
    @Bean  // 比如：请 Spring 调用这个方法，把返回的密码编译器保存为一个可注入的组件
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    // 谁负责执行账号密码认证？
    //  Spring Security 认证管理器 - AuthenticationManager

    /*
        Spring 会先准备好这两个参数
            users -> 项目中注册的 DatabaseUserDetailsService
            encoder -> 上一步方法创建的 PasswordEncoder

        大体流程：过滤器按要求拦截请求，由特定的 Filter 触发 authenticationManager 的认证
            -> ProviderManager 将认证任务交给 DaoAuthenticationProvider
            -> provider 根据 DatabaseUserDetailsService 查询账号信息
            -> provider 根据 PasswordEncoder 校验输入的密码与数据库中密码的哈希
            -> 返回认证结果
     */
    /*
        可以分成四个动作：
            1. Spring 找到已经注册的 DatabaseUserDetailsService。
            2. Spring 找到前面创建的 PasswordEncoder。
            3. 将它们装配进 DaoAuthenticationProvider。
            4. 用 ProviderManager 包装这个认证器，交给 Spring 管理。
     */
    @Bean
    AuthenticationManager authenticationManager(DatabaseUserDetailsService users, PasswordEncoder encoder) {
        // DaoAuthenticationProvider 本身就是为了 “用户查询服务 + 密码校验器” 这种认证方式设计的
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    // 登录后如何记住用户？
    //  HTTP Session
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    // 浏览器提交写请求时如何校验 CSRF？
    //  Session 保存 token，请求头提交 token
    //  写请求如何防止 CSRF
    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    // 登录成功时要处理什么？
    //  更换 Session ID、清理旧 CSRF token
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)
                )
        );
    }

    // 哪些接口允许谁访问？
    //  按路径配置权限 哪些公开、哪些要登录、哪些要管理员
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository contextRepository,
                                            HttpSessionCsrfTokenRepository csrfRepository,
                                            ObjectMapper json) throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf",
                                "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/knowledge/**", "/api/v1/evaluations/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/internal/auth/identity", "/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                // 前端需要 JSON 错误 未登录、无权限时如何返回
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(json, request, response, 401,
                                        "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean csrf = exception instanceof CsrfException;
                            writeError(json, request, response, 403,
                                    csrf ? "CSRF_INVALID" : "FORBIDDEN",
                                    csrf ? "安全令牌无效，请重新获取" : "没有访问权限");
                        }))
                // 退出时做什么？
                //  清理身份、使 Session 失效
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            // LogoutFilter 早于 URL 授权过滤器执行，单独核对之前的登录身份。
                            if (authentication == null
                                    || authentication instanceof AnonymousAuthenticationToken) {
                                writeError(json, request, response, 401,
                                        "UNAUTHENTICATED", "请先登录");
                                return;
                            }
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }));
        return http.build();
    }

    // 前端需要 JSON 错误 未登录、无权限时如何返回
    private static void writeError(ObjectMapper json, HttpServletRequest request,
                                   HttpServletResponse response, int status, String code,
                                   String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        json.writeValue(response.getWriter(), ApiResponses.error(code, message, request));
    }

}
