package com.example.aftersales.order;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;
import static com.example.aftersales.order.OrderRepository.OrderSnapshot;
import static org.junit.jupiter.api.Assertions.*;

class EligibilityServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T02:00:00Z"), ZoneOffset.UTC);
    private final EligibilityService service = new EligibilityService(clock);

    private OrderSnapshot order(int daysAgo, int warrantyDays, OrderStatus status) {
        var date = LocalDate.of(2026, 11, 18).minusDays(daysAgo).atTime(10, 0).atOffset(ZoneOffset.ofHours(8));
        return new OrderSnapshot(UUID.randomUUID(), "TEST", null, status, "299.00",
                status == OrderStatus.CANCELED ? null : date, UUID.randomUUID(), 7, warrantyDays);
    }

    @Test void earphoneBoundaryDayIsIncluded() {
        var result = service.evaluate(order(180, 180, OrderStatus.DELIVERED), false);
        assertTrue(result.eligible());
        assertEquals(LocalDate.of(2026, 11, 18), result.eligibleUntil());
        assertEquals(7, result.ruleVersion());
    }
    @Test void oneDayOutsideBoundaryIsExpired() {
        var result = service.evaluate(order(181, 180, OrderStatus.DELIVERED), false);
        assertFalse(result.eligible());
        assertEquals(java.util.List.of("WARRANTY_EXPIRED"), result.reasonCodes());
    }
    @Test void keyboardUsesItsOwnWarrantyDays() {
        assertTrue(service.evaluate(order(365, 365, OrderStatus.DELIVERED), false).eligible());
        assertFalse(service.evaluate(order(366, 365, OrderStatus.DELIVERED), false).eligible());
    }
    @Test void canceledOrderDoesNotNeedDeliveryDate() {
        var result = service.evaluate(order(0, 180, OrderStatus.CANCELED), false);
        assertFalse(result.eligible());
        assertNull(result.eligibleUntil());
        assertEquals(java.util.List.of("ORDER_CANCELED"), result.reasonCodes());
    }
    @Test void activeTicketBlocksOtherwiseEligibleOrder() {
        var result = service.evaluate(order(30, 180, OrderStatus.DELIVERED), true);
        assertFalse(result.eligible());
        assertEquals(java.util.List.of("ACTIVE_TICKET_EXISTS"), result.reasonCodes());
    }
    @Test void expiredOrderCanAlsoHaveAnActiveTicket() {
        var result = service.evaluate(order(181, 180, OrderStatus.DELIVERED), true);
        assertEquals(java.util.List.of("WARRANTY_EXPIRED", "ACTIVE_TICKET_EXISTS"), result.reasonCodes());
    }
    @Test void deliveryDateUsesShanghaiDateRatherThanUtcDate() {
        var base = order(180, 180, OrderStatus.DELIVERED);
        var crossingMidnight = new OrderSnapshot(base.id(), base.orderNo(), null, base.status(), base.price(),
                OffsetDateTime.parse("2026-05-21T23:30:00Z"), base.policyRuleId(), 7, 180);
        // 该时刻在上海已经是 5 月 22 日，加 180 天为 11 月 18 日。
        assertTrue(service.evaluate(crossingMidnight, false).eligible());
        assertEquals(LocalDate.of(2026, 11, 18), service.evaluate(crossingMidnight, false).eligibleUntil());
    }
}