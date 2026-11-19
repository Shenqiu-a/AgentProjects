package com.example.aftersales.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;

@Repository
public class ProductRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ProductRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // count 和 items 使用同一数据库快照；事务只覆盖两条本地 SQL。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageData<Product> list(PageQuery page, ProductCategory category) {
        String where = category == null ? "" : " WHERE category = :category";
        var args = new MapSqlParameterSource()
                .addValue("limit", page.size()).addValue("offset", page.offset());
        if (category != null) args.addValue("category", category.name());
        long total = jdbc.queryForObject("SELECT count(*) FROM product" + where, args, Long.class);
        var items = jdbc.query("""
                SELECT id, sku, name, category FROM product
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                args, (rs, n) -> new Product(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), ProductCategory.valueOf(rs.getString("category"))));
        return new PageData<>(items, page.page(), page.size(), total);
    }
}
