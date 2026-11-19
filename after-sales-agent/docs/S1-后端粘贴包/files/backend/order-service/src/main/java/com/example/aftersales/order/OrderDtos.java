package com.example.aftersales.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 将小型只读 DTO 放在一起；public record 隐式为 static，不持有外部对象。 */
public final class OrderDtos {
    private OrderDtos() {}

    public enum ProductCategory { EARPHONE, KEYBOARD }
    public enum OrderStatus { DELIVERED, CANCELED }

    public record PageQuery(int page, int size) {
        public PageQuery {
            if (page < 1 || size < 1 || size > 100) {
                throw new IllegalArgumentException("page >= 1; size between 1 and 100");
            }
        }
        public long offset() { return ((long) page - 1) * size; }
    }

    public record PageData<T>(List<T> items, int page, int size, long total) {
        public PageData { items = List.copyOf(items); }
    }

    public record Product(UUID id, String sku, String name, ProductCategory category) {}

    public record OrderSummary(UUID id, String orderNo, Product product, OrderStatus status,
                               String price, OffsetDateTime deliveredAt) {}

    public record Eligibility(boolean eligible, List<String> reasonCodes, LocalDate eligibleUntil,
                              OffsetDateTime evaluatedAt, long ruleVersion) {
        public Eligibility { reasonCodes = List.copyOf(reasonCodes); }
    }

    public record OrderDetail(UUID id, String orderNo, Product product, OrderStatus status,
                              String price, OffsetDateTime deliveredAt, UUID policyRuleId,
                              long policyVersion, Eligibility eligibility) {}
}
