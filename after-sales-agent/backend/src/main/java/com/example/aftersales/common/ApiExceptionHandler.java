package com.example.aftersales.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.security.core.AuthenticationException;

/**
 * 功能：Controller 参数错误和认证错误的统一响应
 * 作者：yml
 * 日期：2026/10/1415:42
 */

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalidRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponses.error("VALIDATION_ERROR", "请检查请求字段和格式", request));
    }

    @ExceptionHandler({InternalAuthenticationServiceException.class})
    public ResponseEntity<?> unavailable(InternalAuthenticationServiceException exception, HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiResponses.error("AUTH_SERVICE_UNAVAILABLE", "认证服务暂不可用", request));
    }

    @ExceptionHandler({AuthenticationException.class})
    public ResponseEntity<?> authenticateFailed(AuthenticationException exception, HttpServletRequest request) {
        boolean login = request.getRequestURI().endsWith("/api/v1/auth/login");
        return ResponseEntity.status(401).body(ApiResponses.error(login ? "INVALID_CREDENTIALS" : "UNAUTHENTICATED",
                login ? "账号或密码不正确" : "请重新登录", request));
    }
}
