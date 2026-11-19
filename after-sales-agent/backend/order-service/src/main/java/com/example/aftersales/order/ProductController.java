package com.example.aftersales.order;

import com.example.aftersales.common.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.example.aftersales.order.OrderDtos.*;

/**
 * 功能：
 * 作者：yml
 * 日期：2026/11/1814:14
 */

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductRepository products;
    public ProductController(ProductRepository products) { this.products = products; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) ProductCategory category, HttpServletRequest request) {
        return ApiResponses.ok(products.list(new PageQuery(page, size), category), request);
    }
}