package com.yangpang.seed.catalog;

import com.yangpang.domain.catalog.ProductSummarySql;
import com.yangpang.domain.catalog.WinnerSql;
import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 경쟁 오퍼 (M3, 아이템 위너). 신선이 아닌 SKU의 약 15%에 다른 판매자 오퍼를 1~2개 더 붙인다.
 * 가격은 기존 오퍼의 -8% ~ +6%. 이후 위너를 다시 계산한다.
 * 주문 생성기(ctx.offers)는 원래 오퍼만 쓴다.
 */
@Component
@Order(45)
@RequiredArgsConstructor
public class CompetingOfferSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "경쟁 오퍼";
    }

    @Override
    public void seed(SeedContext ctx) {
        // 경쟁에 참여하는 판매자: 판매자배송 + 판매자양팡 (직매입 제외)
        List<SeedContext.SellerRef> competitors = ctx.sellers.stream().filter(s -> !"DIRECT".equals(s.type())).toList();
        Long maxId = jdbc.queryForObject("select coalesce(max(id), 0) from vendor_item", Long.class);
        long id = maxId == null ? 0 : maxId;
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        for (SeedContext.Offer o : ctx.offers) {
            if ("FRESH".equals(o.deliveryType()) || !ctx.chance(0.15)) continue;
            Long itemId = jdbc.queryForObject("select item_id from vendor_item where id = ?", Long.class, o.id());
            int extra = ctx.chance(0.3) ? 2 : 1;
            List<Long> used = new ArrayList<>(List.of(o.sellerId()));
            for (int k = 0; k < extra; k++) {
                SeedContext.SellerRef s = competitors.get(ctx.between(0, competitors.size() - 1));
                if (used.contains(s.id())) continue;
                used.add(s.id());
                long price = Math.max(500, Math.round(o.salePrice() * (0.92 + ctx.random.nextDouble() * 0.14) / 10.0) * 10);
                long list = Math.max(price, Math.round(price * 1.15 / 100.0) * 100);
                String delivery = "FULFILLMENT".equals(s.type()) ? "FULFILLMENT" : "SELLER";
                Instant created = now.minus(Duration.ofDays(ctx.between(1, 200)));
                rows.add(new Object[]{++id, itemId, s.id(), list, price, ctx.between(0, 10) == 0 ? 0 : ctx.between(5, 80),
                        delivery, 99, Timestamp.from(created), Timestamp.from(created)});
            }
        }
        jdbc.batchUpdate("""
                insert into vendor_item(id, item_id, seller_id, list_price, sale_price, stock, delivery_type, max_per_order,
                                        created_at, updated_at, is_winner)
                values (?,?,?,?,?,?,?,?,?,?, false)""", rows);
        jdbc.execute("select setval(pg_get_serial_sequence('vendor_item', 'id'), (select max(id) from vendor_item))");
        jdbc.update(WinnerSql.RECOMPUTE_ALL);
        jdbc.update(ProductSummarySql.REFRESH_ALL);
    }
}
