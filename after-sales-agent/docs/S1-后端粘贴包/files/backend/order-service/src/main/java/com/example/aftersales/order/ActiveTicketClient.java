package com.example.aftersales.order;

import com.example.aftersales.platform.ServiceApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Component
public class ActiveTicketClient {
    private final URI origin;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ActiveTicketClient(@Value("${app.services.agent-url}") String agentUrl,
                              @Value("${app.services.auth-token}") String token,
                              ObjectMapper json) {
        origin = URI.create(agentUrl);
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("AGENT_SERVICE_URL must be an HTTP(S) origin");
        }
        if (token == null || token.length() < 32 || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("Invalid SERVICE_AUTH_TOKEN");
        }
        this.token = token;
        this.json = json;
    }

    public boolean exists(UUID userId, UUID orderId) {
        URI endpoint = origin.resolve("/internal/tickets/active?userId=" + userId + "&orderId=" + orderId);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json").header("X-Service-Token", token).GET().build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
        if (response.statusCode() != 200) throw unavailable();
        try {
            var body = json.readTree(response.body());
            if (body == null || !body.isObject() || !body.path("hasActiveTicket").isBoolean()) {
                throw unavailable();
            }
            return body.path("hasActiveTicket").asBoolean();
        } catch (ServiceApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private ServiceApiException unavailable() {
        return new ServiceApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", "暂时无法核验活动工单，请稍后重试");
    }
}
