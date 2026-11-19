package com.example.aftersales.order;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(basePackageClasses = OrderController.class)
public class OrderDatabaseExceptionHandler {
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<?> unavailable(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiResponses.error(
                "DEPENDENCY_UNAVAILABLE", "订单数据库暂不可用，请稍后重试", request));
    }
}
