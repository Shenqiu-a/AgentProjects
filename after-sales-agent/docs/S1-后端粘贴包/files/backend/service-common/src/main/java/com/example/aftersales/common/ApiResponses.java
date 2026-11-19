package com.example.aftersales.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;

public final class ApiResponses {
    private static final String REQUEST_ID = ApiResponses.class.getName();
    private ApiResponses() {}

    public static Map<String, Object> ok(Object data, HttpServletRequest request) {
        return Map.of("code", "OK", "message", "success", "data", data,
                "requestId", requestId(request));
    }

    public static Map<String, Object> error(String code, String message, HttpServletRequest request) {
        return Map.of("code", code, "message", message, "details", Map.of(),
                "requestId", requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID);
        if (existing != null) return existing.toString();
        String value = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, value);
        return value;
    }
}
