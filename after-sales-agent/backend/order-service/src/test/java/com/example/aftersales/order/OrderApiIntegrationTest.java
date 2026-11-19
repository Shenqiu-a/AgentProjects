package com.example.aftersales.order;

import com.example.aftersales.platform.ServiceSecurityConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 必须指向专用空测试库；测试会运行迁移、加载 S1 样例，不连接开发库。 */
@EnabledIfEnvironmentVariable(named = "S1_ORDER_TEST_URL", matches = ".+")
class OrderApiIntegrationTest {
    private static DriverManagerDataSource dataSource;
    private HttpServer upstream;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final AtomicInteger ticketRequests = new AtomicInteger();
    private volatile boolean ticketServiceDown;
    private static final String ALICE = "00000000-0000-4000-8000-000000000001";
    private static final String BOB = "00000000-0000-4000-8000-000000000002";
    private static String order(int n) { return "20000000-0000-4000-8000-%012d".formatted(n); }

    @BeforeAll static void database() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("S1_ORDER_TEST_URL"), "s1tester", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        try (var input = OrderApiIntegrationTest.class.getResourceAsStream("/s1-orders.sql")) {
            assertNotNull(input);
            new JdbcTemplate(dataSource).execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @BeforeEach void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/internal/auth/identity", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            int status = cookie == null ? 401 : 200;
            boolean bob = "SESSION=bob".equals(cookie);
            boolean admin = "SESSION=admin".equals(cookie);
            String json = status == 401 ? "{}" : "{\"id\":\"" + (bob ? BOB : ALICE)
                    + "\",\"username\":\"user\",\"role\":\"" + (admin ? "ADMIN" : "CUSTOMER") + "\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        upstream.createContext("/internal/tickets/active", exchange -> {
            ticketRequests.incrementAndGet();
            boolean active = exchange.getRequestURI().getQuery().contains(order(6));
            byte[] bytes = ("{\"hasActiveTicket\":" + active + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ticketServiceDown ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        upstream.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.services.auth-url=" + origin, "app.services.agent-url=" + origin,
                "app.services.auth-token=isolated-test-service-secret-at-least-32-characters");
        context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void stop() { if (context != null) context.close(); if (upstream != null) upstream.stop(0); }

    @Test void productPaginationCategoryAndMoneyContract() throws Exception {
        mvc.perform(get("/api/v1/products").header("Cookie", "SESSION=alice").param("category", "KEYBOARD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].category").value("KEYBOARD"));
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=alice").param("orderNo", "A1001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].price").value("299.00"))
                .andExpect(jsonPath("$.data.items[0].product.category").value("EARPHONE"));
        assertEquals(0, ticketRequests.get());
    }
    @Test void aliceAndBobRemainIsolatedDespiteForgedUserId() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=alice").param("userId", BOB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("B2001"));
        mvc.perform(get("/api/v1/orders/" + order(4)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertEquals(0, ticketRequests.get());
    }
    @Test void detailIncludesRuleAndEligibilityWithCorrectBoundaries() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order(1)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligibility.eligible").value(true))
                .andExpect(jsonPath("$.data.policyVersion").value(1));
        mvc.perform(get("/api/v1/orders/" + order(7)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligibility.eligible").value(true));
        mvc.perform(get("/api/v1/orders/" + order(8)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligibility.reasonCodes[0]").value("WARRANTY_EXPIRED"));
        mvc.perform(get("/api/v1/orders/" + order(3)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveredAt").isEmpty())
                .andExpect(jsonPath("$.data.eligibility.reasonCodes[0]").value("ORDER_CANCELED"));
        mvc.perform(get("/api/v1/orders/" + order(6)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligibility.reasonCodes[0]").value("ACTIVE_TICKET_EXISTS"));
    }
    @Test void unauthenticatedAndAdminRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=admin")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/" + order(1)).header("Cookie", "SESSION=admin")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/products").header("Cookie", "SESSION=admin")).andExpect(status().isOk());
        assertEquals(0, ticketRequests.get());
    }
    @Test void badParametersAndSqlInjectionTextDoNotEscapeFiltering() throws Exception {
        for (String path : new String[]{"/api/v1/orders?page=0", "/api/v1/orders?size=101",
                "/api/v1/orders?status=WRONG", "/api/v1/orders/not-uuid", "/api/v1/products?category=WRONG"}) {
            mvc.perform(get(path).header("Cookie", "SESSION=alice")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details").exists()).andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=alice").param("orderNo", "A1001' OR '1'='1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void filterAndOutOfRangePageHaveStableTotals() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=alice").param("status", "CANCELED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/orders").header("Cookie", "SESSION=alice").param("page", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void dependencyFailureReturns503RatherThanEligible() throws Exception {
        ticketServiceDown = true;
        mvc.perform(get("/api/v1/orders/" + order(1)).header("Cookie", "SESSION=alice"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc @EnableWebSecurity @EnableTransactionManagement
    @Import({ServiceSecurityConfiguration.class, OrderController.class, ProductController.class,
            OrderService.class, OrderRepository.class, ProductRepository.class,
            EligibilityService.class, ActiveTicketClient.class, OrderDatabaseExceptionHandler.class})
    static class Config {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean NamedParameterJdbcTemplate jdbc(DataSource ds) { return new NamedParameterJdbcTemplate(ds); }
        @Bean JdbcTransactionManager transactionManager(DataSource ds) { return new JdbcTransactionManager(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.fixed(LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .atTime(10, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneOffset.UTC); }
    }
}