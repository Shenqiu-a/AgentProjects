package com.example.aftersales.order;

import com.example.aftersales.common.ApiResponses;
import com.example.aftersales.platform.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;

/** 所有订单查询都在 SQL 中包含当前客户 ID；管理员通过这里也不能读取其他人的订单。 */
@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders = orders; }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal Caller caller,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String orderNo,
                                    @RequestParam(required = false) OrderStatus status, HttpServletRequest request) {
        return ApiResponses.ok(orders.list(caller.id(), new PageQuery(page, size), orderNo, status), request);
    }

    @GetMapping("/{orderId}")
    public Map<String, Object> detail(@PathVariable UUID orderId,
                                      @AuthenticationPrincipal Caller caller, HttpServletRequest request) {
        return ApiResponses.ok(orders.detail(caller.id(), orderId), request);
    }
}
