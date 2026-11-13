package com.example.aftersales.platform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceSecurityFlowTest {
    private HttpServer server;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private String role = "CUSTOMER";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/auth/identity", exchange -> {
            byte[] data = ("{\"id\":\"10000000-0000-4000-8000-000000000001\",\"username\":\"alice\",\"role\":\"" + role + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);exchange.getResponseBody().write(data);exchange.close();
        });
        server.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.services.auth-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "app.services.auth-token=test-service-secret-with-at-least-32-characters");
        context.register(Config.class);context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void stop() { context.close();server.stop(0); }

    @Test void customerCannotAccessKnowledgeEvenWithForgedRoleHeader() throws Exception {
        mvc.perform(get("/api/v1/knowledge/documents").header("X-User-Role", "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders")).andExpect(status().isOk());
    }

    @Test void adminCanAccessKnowledge() throws Exception {
        role = "ADMIN";
        mvc.perform(get("/api/v1/knowledge/documents")).andExpect(status().isOk());
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    @Import({ServiceSecurityConfiguration.class, Endpoints.class})
    static class Config { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }

    @RestController
    static class Endpoints {
        @GetMapping({"/api/v1/orders", "/api/v1/knowledge/documents"})
        String ok() { return "ok"; }
    }
}
