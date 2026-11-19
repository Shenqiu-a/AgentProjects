package com.example.aftersales.order;

import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import static com.example.aftersales.order.OrderClockConfiguration.BUSINESS_ZONE;
import static com.example.aftersales.order.OrderDtos.*;
import static com.example.aftersales.order.OrderRepository.OrderSnapshot;

@Service
public class EligibilityService {
    private final Clock clock;
    public EligibilityService(Clock clock) { this.clock = clock; }

    public Eligibility evaluate(OrderSnapshot order, boolean hasActiveTicket) {
        // 一次计算只读取一次当前时间，避免恰好跨零点时产生矛盾结果。
        var evaluatedAt = clock.instant().atZone(BUSINESS_ZONE).toOffsetDateTime();
        LocalDate today = evaluatedAt.toLocalDate();
        LocalDate eligibleUntil = null;
        var reasons = new ArrayList<String>();
        if (order.status() == OrderStatus.CANCELED) {
            reasons.add("ORDER_CANCELED");
        } else {
            var deliveredAt = Objects.requireNonNull(order.deliveredAt(), "Delivered order needs date");
            eligibleUntil = deliveredAt.atZoneSameInstant(BUSINESS_ZONE)
                    .toLocalDate().plusDays(order.warrantyDays());
            if (today.isAfter(eligibleUntil)) reasons.add("WARRANTY_EXPIRED");
        }
        if (hasActiveTicket) reasons.add("ACTIVE_TICKET_EXISTS");
        return new Eligibility(reasons.isEmpty(), reasons, eligibleUntil, evaluatedAt, order.ruleVersion());
    }
}
