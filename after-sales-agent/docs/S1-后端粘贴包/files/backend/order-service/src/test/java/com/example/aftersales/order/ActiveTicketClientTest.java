package com.example.aftersales.order;

import com.example.aftersales.platform.ServiceApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ActiveTicketClientTest {
    private HttpServer server;
    private String body;
    private int status;
    private String receivedToken;
    private String receivedCookie;
    private final String token = "test-ticket-service-secret-at-least-32-characters";
    private ActiveTicketClient client;

    @BeforeEach void start() throws Exception {
        body = "{\"hasActiveTicket\":false}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tickets/active", exchange -> {
            receivedToken = exchange.getRequestHeaders().getFirst("X-Service-Token");
            receivedCookie = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (status == 302) exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new ActiveTicketClient("http://127.0.0.1:" + server.getAddress().getPort(), token, new ObjectMapper());
    }
    @AfterEach void stop() { server.stop(0); }
    private boolean query() { return client.exists(UUID.randomUUID(), UUID.randomUUID()); }

    @Test void parsesBothBooleanResultsAndUsesOnlyServerCredential() {
        assertFalse(query());
        body = "{\"hasActiveTicket\":true}";
        assertTrue(query());
        assertEquals(token, receivedToken);
        assertNull(receivedCookie);
    }
    @Test void upstreamFailureIsNotTreatedAsNoTicket() {
        status = 500;
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ServiceApiException.class, this::query).code());
    }
    @Test void missingFieldIsNotTreatedAsFalse() {
        body = "{}";
        assertThrows(ServiceApiException.class, this::query);
    }
    @Test void stringFalseIsRejected() {
        body = "{\"hasActiveTicket\":\"false\"}";
        assertThrows(ServiceApiException.class, this::query);
    }
    @Test void malformedJsonIsRejected() {
        body = "not-json";
        assertThrows(ServiceApiException.class, this::query);
    }
    @Test void redirectIsRejected() {
        status = 302;
        assertThrows(ServiceApiException.class, this::query);
    }
    @Test void connectionFailureReturnsDependencyError() {
        server.stop(0);
        assertThrows(ServiceApiException.class, this::query);
    }
}
