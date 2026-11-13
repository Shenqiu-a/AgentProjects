package com.example.aftersales.security;

import com.example.aftersales.common.ApiExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 无外部服务也执行真实 SecurityFilterChain、SessionRepositoryFilter、Cookie 和 CSRF 流程。 */
class AuthSecurityFlowTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String BODY = "{\"username\":\"alice\",\"password\":\"a-valid-password\"}";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private MapSessionRepository sessions;
    private LoginRateLimiter limiter;
    private AppUserRepository users;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.security.login-rate-limit.namespace=test:login",
                "app.services.auth-token=test-service-secret-with-at-least-32-characters",
                "app.security.login-rate-limit.ip.limit=30",
                "app.security.login-rate-limit.ip.window-seconds=60",
                "app.security.login-rate-limit.account-ip.limit=5",
                "app.security.login-rate-limit.account-ip.window-seconds=300",
                "app.security.login-rate-limit.account.limit=20",
                "app.security.login-rate-limit.account.window-seconds=300");
        context.register(TestConfiguration.class);
        context.refresh();
        users = context.getBean(AppUserRepository.class);
        limiter = context.getBean(LoginRateLimiter.class);
        json = context.getBean(ObjectMapper.class);
        sessions = spy(new MapSessionRepository(new ConcurrentHashMap<>()));
        var sessionFilter = new SessionRepositoryFilter<MapSession>(sessions);
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName("JSESSIONID");
        var resolver = new CookieHttpSessionIdResolver();
        resolver.setCookieSerializer(cookies);
        sessionFilter.setHttpSessionIdResolver(resolver);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new RedisAvailabilityFilter(json), sessionFilter)
                .apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void missingCsrfIsRejectedBeforeLimiterAndAuthentication() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(limiter, users);
    }

    @Test
    void loginRotatesSessionAndCsrfThenLogoutInvalidatesIdentity() throws Exception {
        var anonymous = csrf(null);
        var login = mvc.perform(post(LOGIN).cookie(anonymous.cookie())
                        .header("X-CSRF-TOKEN", anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();
        Cookie authenticated = login.getResponse().getCookie("JSESSIONID");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(anonymous.cookie().getValue());

        mvc.perform(get("/api/v1/auth/me").cookie(authenticated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"));
        mvc.perform(post("/api/v1/auth/logout").cookie(authenticated)
                        .header("X-CSRF-TOKEN", anonymous.token()))
                .andExpect(status().isForbidden());
        var current = csrf(authenticated);
        mvc.perform(post("/api/v1/auth/logout").cookie(current.cookie())
                        .header("X-CSRF-TOKEN", current.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(current.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitedLoginKeepsAnExistingAuthenticatedSession() throws Exception {
        var anonymous = csrf(null);
        var result = mvc.perform(post(LOGIN).cookie(anonymous.cookie())
                        .header("X-CSRF-TOKEN", anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();
        var current = csrf(result.getResponse().getCookie("JSESSIONID"));
        doThrow(new LoginRateLimitException(10)).when(limiter).check(anyString(), eq("alice"));
        clearInvocations(users);

        mvc.perform(post(LOGIN).cookie(current.cookie()).header("X-CSRF-TOKEN", current.token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "10"));
        verifyNoInteractions(users);
        mvc.perform(get("/api/v1/auth/me").cookie(current.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void sessionStorageFailureWithARealCookieReturns503BeforeAuthentication() throws Exception {
        var anonymous = csrf(null);
        doThrow(new RedisConnectionFailureException("test session store is unavailable"))
                .when(sessions).findById(anyString());

        mvc.perform(post(LOGIN).cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(limiter, users);
    }

    @Test
    void internalIdentityRequiresServiceTokenAndRealSession() throws Exception {
        var current = authenticatedCsrf();
        mvc.perform(get("/internal/auth/identity").cookie(current.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/auth/identity").cookie(current.cookie())
                        .header("X-Service-Token", "test-service-secret-with-at-least-32-characters"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(get("/internal/auth/identity")
                        .header("X-Service-Token", "test-service-secret-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalWriteIdentityRequiresCsrfFromSameSession() throws Exception {
        var current = authenticatedCsrf();
        mvc.perform(post("/internal/auth/identity").cookie(current.cookie())
                        .header("X-Service-Token", "test-service-secret-with-at-least-32-characters"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/internal/auth/identity").cookie(current.cookie())
                        .header("X-Service-Token", "test-service-secret-with-at-least-32-characters")
                        .header("X-CSRF-TOKEN", current.token()))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountCannotBeUsedByAnotherService() throws Exception {
        var current = authenticatedCsrf();
        when(users.findByUserName("alice")).thenReturn(Optional.empty());
        mvc.perform(get("/internal/auth/identity").cookie(current.cookie())
                        .header("X-Service-Token", "test-service-secret-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
    }

    private Csrf authenticatedCsrf() throws Exception {
        var anonymous = csrf(null);
        var login = mvc.perform(post(LOGIN).cookie(anonymous.cookie())
                        .header("X-CSRF-TOKEN", anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();
        return csrf(login.getResponse().getCookie("JSESSIONID"));
    }

    private Csrf csrf(Cookie existingCookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (existingCookie != null) {
            request.cookie(existingCookie);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("JSESSIONID");
        if (cookie == null) {
            cookie = existingCookie;
        }
        assertThat(cookie).isNotNull();
        String token = json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return new Csrf(cookie, token);
    }

    private record Csrf(Cookie cookie, String token) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AuthController.class, ApiExceptionHandler.class, InternalAuthController.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LoginRateLimiter loginRateLimiter() {
            return mock(LoginRateLimiter.class);
        }

        @Bean
        AppUserRepository appUserRepository(PasswordEncoder encoder) {
            var users = mock(AppUserRepository.class);
            when(users.findByUserName("alice")).thenReturn(Optional.of(new AppUserRepository.Account(
                    UUID.randomUUID(), "alice", encoder.encode("a-valid-password"), "CUSTOMER", true)));
            return users;
        }

        @Bean
        DatabaseUserDetailsService databaseUserDetailsService(AppUserRepository users) {
            return new DatabaseUserDetailsService(users);
        }
    }
}
