package com.example.aftersales.platform;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ServiceExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException exception, HttpServletRequest request) {
        int status = exception.getStatusCode().value();
        return ResponseEntity.status(status).body(ApiResponses.error(
                status == 404 ? "NOT_FOUND" : "REQUEST_FAILED", "请求的资源不存在或不可用", request));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponses.error("VALIDATION_ERROR", "请检查请求参数", request));
    }
}
