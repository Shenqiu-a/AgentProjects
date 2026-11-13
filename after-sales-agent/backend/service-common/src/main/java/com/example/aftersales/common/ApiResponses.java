package com.example.aftersales.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.UUID;

/**
 * 功能：成功/失败响应外壳；避免泄露密码和异常内部信息
 * 作者：yml
 * 日期：2026/10/1415:41
 */

public final class ApiResponses {
    private static final String REQUEST_ID = ApiResponses.class.getName();

    private ApiResponses(){};

    public static Map<String, Object> ok(Object data, HttpServletRequest request){
        return Map.of("code", "OK", "message", "success", "data", data, "requestId", requestId(request));
    }

    public static Map<String, Object> error(String code, String message, HttpServletRequest request) {
        return Map.of("code", code, "message", message, "detials", Map.of("requestId",requestId(request)));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID);
        if (value == null) {
            value = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID,value);
        }
        return value.toString();
    }
}
