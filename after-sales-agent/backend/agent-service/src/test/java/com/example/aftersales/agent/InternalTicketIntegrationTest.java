package com.example.aftersales.agent;

import com.example.aftersales.platform.ServiceSecurityConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "S1_AGENT_TEST_URL", matches = ".+")
class InternalTicketIntegrationTest {
    private static final String TOKEN = "test-internal-service-secret-at-least-32-characters";
    private static final String USER = "00000000-0000-4000-8000-000000000001";
    private static final String ORDER = "20000000-0000-4000-8000-000000000006";
    private static DriverManagerDataSource dataSource;
    private AnnotationConfigWebApplicationContext context;
    private HttpServer auth;
    private MockMvc mvc;
    private final AtomicInteger authRequests = new AtomicInteger();

    @BeforeAll static void database() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("S1_AGENT_TEST_URL"), "s1tester", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        try (var input = InternalTicketIntegrationTest.class.getResourceAsStream("/s1-active-ticket.sql")) {
            assertNotNull(input);
            new JdbcTemplate(dataSource).execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @BeforeEach void start() throws Exception {
        new JdbcTemplate(dataSource).update("UPDATE ticket SET status = 'OPEN' WHERE order_id = ?::uuid", ORDER);
        auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth.createContext("/internal/auth/identity", exchange -> {
            authRequests.incrementAndGet();
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        auth.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.services.auth-url=http://127.0.0.1:" + auth.getAddress().getPort(),
                "app.services.auth-token=" + TOKEN);
        context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void stop() { if (context != null) context.close(); if (auth != null) auth.stop(0); }

    @Test void noOrWrongServiceTokenIsRejectedEvenWithForgedHeaders() throws Exception {
        mvc.perform(get("/internal/tickets/active").param("userId", USER).param("orderId", ORDER)
                        .header("Cookie", "SESSION=fake").header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/tickets/active").param("userId", USER).param("orderId", ORDER)
                .header("X-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        assertEquals(0, authRequests.get());
    }
    @Test void validServiceTokenUsesInternalChainRatherThanCookieAuthentication() throws Exception {
        mvc.perform(get("/internal/tickets/active").param("userId", USER).param("orderId", ORDER)
                        .header("X-Service-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveTicket").value(true));
        assertEquals(0, authRequests.get());
    }
    @Test void anotherOwnerCannotMatchThisTicket() throws Exception {
        mvc.perform(get("/internal/tickets/active").param("userId", "00000000-0000-4000-8000-000000000002")
                        .param("orderId", ORDER).header("X-Service-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasActiveTicket").value(false));
    }
    @Test void onlyOpenAndProcessingAreActive() throws Exception {
        for (String state : new String[]{"OPEN", "PROCESSING", "RESOLVED", "CLOSED"}) {
            new JdbcTemplate(dataSource).update("UPDATE ticket SET status = ? WHERE order_id = ?::uuid", state, ORDER);
            mvc.perform(get("/internal/tickets/active").param("userId", USER).param("orderId", ORDER)
                            .header("X-Service-Token", TOKEN)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveTicket").value(state.equals("OPEN") || state.equals("PROCESSING")));
        }
    }
    @Test void tokenDoesNotAuthorizeWritesOrPublicCustomerEndpoints() throws Exception {
        mvc.perform(post("/internal/tickets/active").header("X-Service-Token", TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tickets/54000000-0000-4000-8000-000000000001")
                .header("X-Service-Token", TOKEN)).andExpect(status().isUnauthorized());
        assertEquals(1, authRequests.get());
    }
    @Test void missingParameterReturnsAValidationError() throws Exception {
        mvc.perform(get("/internal/tickets/active").header("X-Service-Token", TOKEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    @Import({ServiceSecurityConfiguration.class, InternalTicketSecurityConfiguration.class,
            InternalTicketController.class, ActiveTicketRepository.class, TicketController.class})
    static class Config {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean NamedParameterJdbcTemplate jdbc(DataSource ds) { return new NamedParameterJdbcTemplate(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}