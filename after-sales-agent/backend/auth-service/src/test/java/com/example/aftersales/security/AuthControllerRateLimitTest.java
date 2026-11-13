package com.example.aftersales.security;

import com.example.aftersales.common.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller 边界测试；过滤器流程见 AuthSecurityFlowTest，真实 Redis 存储见 RedisLoginIntegrationTest。 */
class AuthControllerRateLimitTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String BODY = "{\"username\":\"alice\",\"password\":\"a-valid-password\"}";

    private AuthenticationManager authenticationManager;
    private SessionAuthenticationStrategy sessionStrategy;
    private SecurityContextRepository contexts;
    private AppUserRepository users;
    private LoginRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        sessionStrategy = mock(SessionAuthenticationStrategy.class);
        contexts = mock(SecurityContextRepository.class);
        users = mock(AppUserRepository.class);
        limiter = mock(LoginRateLimiter.class);
        var controller = new AuthController(authenticationManager, sessionStrategy, contexts, users, limiter);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rateLimitReturns429BeforeAuthenticationAndKeepsExistingSession() throws Exception {
        doThrow(new LoginRateLimitException(12)).when(limiter).check(anyString(), eq("alice"));
        var session = new MockHttpSession();
        session.setAttribute("existing-session", "kept");

        mvc.perform(post(LOGIN).session(session).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute("existing-session")).isEqualTo("kept");
        verifyNoInteractions(authenticationManager, sessionStrategy, contexts, users);
    }

    @Test
    void unavailableRateLimiterReturns503BeforeAuthentication() throws Exception {
        doThrow(new InternalAuthenticationServiceException("test unavailable"))
                .when(limiter).check(anyString(), eq("alice"));

        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"));

        verifyNoInteractions(authenticationManager, sessionStrategy, contexts, users);
    }

    @Test
    void badPasswordStillReturns401AndClearsTheOldSession() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("test invalid credentials"));
        var session = new MockHttpSession();

        mvc.perform(post(LOGIN).session(session).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        var order = inOrder(limiter, authenticationManager);
        order.verify(limiter).check(anyString(), eq("alice"));
        order.verify(authenticationManager).authenticate(any());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void successfulLoginStillSavesTheSecurityContext() throws Exception {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("alice", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(users.findByUserName("alice")).thenReturn(Optional.of(
                new AppUserRepository.Account(UUID.randomUUID(), "alice", "not-returned", "CUSTOMER", true)));

        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        var order = inOrder(limiter, authenticationManager, sessionStrategy, contexts);
        order.verify(limiter).check(anyString(), eq("alice"));
        order.verify(authenticationManager).authenticate(any());
        order.verify(sessionStrategy).onAuthentication(eq(authentication), any(), any());
        order.verify(contexts).saveContext(any(), any(), any());
    }

    @Test
    void invalidRequestStillReturns400WithoutUsingLoginQuota() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"al\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(limiter, authenticationManager);
    }
}
