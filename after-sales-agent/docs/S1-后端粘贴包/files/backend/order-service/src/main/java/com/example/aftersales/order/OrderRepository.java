package com.example.aftersales.order;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;

@Repository
public class OrderRepository {
    private static final String FROM = """
            FROM customer_order o
            JOIN product p ON p.id = o.product_id
            JOIN policy_rule r ON r.id = o.policy_rule_id
            """;
    private static final String SELECT = """
            SELECT o.id, o.order_no, o.status, o.price, o.delivered_at,
                   o.policy_rule_id, r.version AS rule_version, r.warranty_days,
                   p.id AS product_id, p.sku, p.name, p.category
            """ + FROM;
    private static final RowMapper<OrderSnapshot> MAPPER = (rs, n) -> new OrderSnapshot(
            rs.getObject("id", UUID.class), rs.getString("order_no"),
            new Product(rs.getObject("product_id", UUID.class), rs.getString("sku"),
                    rs.getString("name"), ProductCategory.valueOf(rs.getString("category"))),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("price").setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            rs.getObject("delivered_at", OffsetDateTime.class),
            rs.getObject("policy_rule_id", UUID.class), rs.getLong("rule_version"),
            rs.getInt("warranty_days"));

    private final NamedParameterJdbcTemplate jdbc;
    public OrderRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageData<OrderSummary> listOwned(UUID userId, PageQuery page,
                                            String orderNo, OrderStatus status) {
        StringBuilder where = new StringBuilder(" WHERE o.user_id = :userId");
        var args = new MapSqlParameterSource("userId", userId)
                .addValue("limit", page.size()).addValue("offset", page.offset());
        if (orderNo != null) {
            where.append(" AND o.order_no = :orderNo");
            args.addValue("orderNo", orderNo);
        }
        if (status != null) {
            where.append(" AND o.status = :status");
            args.addValue("status", status.name());
        }
        long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, args, Long.class);
        var items = jdbc.query(SELECT + where
                + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit OFFSET :offset", args, MAPPER)
                .stream().map(OrderSnapshot::summary).toList();
        return new PageData<>(items, page.page(), page.size(), total);
    }

    public Optional<OrderSnapshot> findOwned(UUID userId, UUID orderId) {
        return jdbc.query(SELECT + " WHERE o.id = :orderId AND o.user_id = :userId",
                Map.of("orderId", orderId, "userId", userId), MAPPER).stream().findFirst();
    }

    /** 数据库查询快照，不作为接口响应直接序列化。 */
    public record OrderSnapshot(UUID id, String orderNo, Product product, OrderStatus status,
                                String price, OffsetDateTime deliveredAt, UUID policyRuleId,
                                long ruleVersion, int warrantyDays) {
        public OrderSummary summary() {
            return new OrderSummary(id, orderNo, product, status, price, deliveredAt);
        }
    }
}
