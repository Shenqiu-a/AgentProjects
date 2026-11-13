package com.example.aftersales.platform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class RemoteIdentityFilterTest {
    private HttpServer server;
    private RemoteIdentityFilter filter;
    private int status;
    private String body;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private final AtomicReference<String> csrf = new AtomicReference<>();
    private final AtomicReference<String> serviceToken = new AtomicReference<>();
    private static final String TOKEN = "test-service-secret-with-at-least-32-characters";

    @BeforeEach void start() throws Exception {
        status = 200;
        body = "{\"id\":\"10000000-0000-4000-8000-000000000001\",\"username\":\"alice\",\"role\":\"CUSTOMER\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/auth/identity", exchange -> {
            method.set(exchange.getRequestMethod());
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            csrf.set(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
            serviceToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=renewed; HttpOnly");
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        filter = new RemoteIdentityFilter("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, new ObjectMapper());
    }

    @AfterEach void stop() { server.stop(0); SecurityContextHolder.clearContext(); }

    @Test void identityComesFromAuthServiceAndContextIsCleared() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("Cookie", "JSESSIONID=valid");
        request.addHeader("X-User-Role", "ADMIN");
        request.addHeader("X-Service-Token", "forged");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isInstanceOf(Caller.class);
            assertThat(authentication.getName()).isEqualTo("alice");
            assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_CUSTOMER");
        });
        assertThat(method.get()).isEqualTo("GET");
        assertThat(cookie.get()).isEqualTo("JSESSIONID=valid");
        assertThat(serviceToken.get()).isEqualTo(TOKEN);
        assertThat(response.getHeader("Set-Cookie")).contains("renewed");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void everyWriteIsCheckedWithPostAndCsrf(String verb) throws Exception {
        var request = new MockHttpServletRequest(verb, "/api/v1/tickets");
        request.addHeader("X-CSRF-TOKEN", "browser-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(method.get()).isEqualTo("POST");
        assertThat(csrf.get()).isEqualTo("browser-token");
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 500, 503})
    void authFailureNeverReachesBusinessCode(int code) throws Exception {
        status = code;
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                (req, res) -> fail("Unauthenticated request reached business code"));
        assertThat(response.getStatus()).isEqualTo(code == 401 || code == 403 ? code : 503);
    }

    @Test void malformedIdentityFailsClosed() throws Exception {
        body = "{}";
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                (req, res) -> fail("Invalid identity reached business code"));
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test void unavailableAuthFailsClosed() throws Exception {
        server.stop(0);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response,
                (req, res) -> fail("Unavailable authentication was bypassed"));
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test void healthDoesNotRequireAuthService() throws Exception {
        server.stop(0);
        var reached = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertThat(reached.get()).isTrue();
        assertThat(method.get()).isNull();
    }
}
