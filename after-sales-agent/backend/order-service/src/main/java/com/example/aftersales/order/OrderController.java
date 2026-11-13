package com.example.aftersales.order;

import com.example.aftersales.common.ApiResponses;
import com.example.aftersales.platform.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** 所有订单查询都在 SQL 中包含当前客户 ID；管理员通过这里也不能读取其他人的订单。 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private static final String SELECT = """
            SELECT o.id, o.order_no AS "orderNo", o.status, o.price,
                   o.delivered_at AS "deliveredAt", o.version, p.name AS "productName",
                   o.product_id AS "productId", o.policy_rule_id AS "policyRuleId"
            FROM customer_order o JOIN product p ON p.id = o.product_id
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public OrderController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal Caller caller, HttpServletRequest request,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        var rows = jdbc.queryForList(SELECT + """
                WHERE o.user_id = :userId ORDER BY o.created_at DESC, o.id
                LIMIT :limit OFFSET :offset
                """, Map.of("userId", caller.id(), "limit", limit, "offset", offset));
        return ApiResponses.ok(Map.of("items", rows, "offset", offset, "limit", limit), request);
    }

    @GetMapping("/{orderId}")
    public Map<String, Object> detail(@PathVariable UUID orderId, @AuthenticationPrincipal Caller caller,
                                      HttpServletRequest request) {
        var rows = jdbc.queryForList(SELECT + " WHERE o.id = :id AND o.user_id = :userId",
                Map.of("id", orderId, "userId", caller.id()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ApiResponses.ok(rows.getFirst(), request);
    }
}
