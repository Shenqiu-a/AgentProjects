package com.example.aftersales.order;

import com.example.aftersales.platform.ServiceApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;

/**
 * 功能：
 * 作者：yml
 * 日期：2026/11/1814:13
 */

@Service
public class OrderService {
    private final OrderRepository orders;
    private final EligibilityService eligibility;
    private final ActiveTicketClient tickets;

    public OrderService(OrderRepository orders, EligibilityService eligibility, ActiveTicketClient tickets) {
        this.orders = orders;
        this.eligibility = eligibility;
        this.tickets = tickets;
    }

    public PageData<OrderSummary> list(UUID userId, PageQuery page, String orderNo, OrderStatus status) {
        if (orderNo != null) {
            orderNo = orderNo.strip();
            if (orderNo.isEmpty() || orderNo.length() > 64) {
                throw new IllegalArgumentException("Invalid orderNo");
            }
        }
        return orders.listOwned(userId, page, orderNo, status);
    }

    // 不加 @Transactional：本地查询结束后，再请求工单服务，不长期占用数据库连接。
    public OrderDetail detail(UUID userId, UUID orderId) {
        var order = orders.findOwned(userId, orderId).orElseThrow(() -> new ServiceApiException(
                HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "未找到可访问的订单"));
        boolean active = tickets.exists(userId, order.id());
        var result = eligibility.evaluate(order, active);
        return new OrderDetail(order.id(), order.orderNo(), order.product(), order.status(), order.price(),
                order.deliveredAt(), order.policyRuleId(), order.ruleVersion(), result);
    }
}
