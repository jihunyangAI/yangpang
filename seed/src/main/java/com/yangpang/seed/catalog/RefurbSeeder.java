package com.yangpang.seed.catalog;

import com.yangpang.domain.catalog.ItemCondition;
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
 * 리퍼마켓 상품 (M3): 신선식품이 아닌 양팡배송 오퍼 약 60개에 리퍼 오퍼를 1~3개 재고로 붙인다.
 * 이후 실제 불량·파손 반품이 검수 완료되면 RefurbService가 같은 방식으로 추가한다.
 */
@Component
@Order(46)
@RequiredArgsConstructor
public class RefurbSeeder implements Seeder {

    private static final ItemCondition[] GRADES = {ItemCondition.REFURB_BEST, ItemCondition.REFURB_GOOD, ItemCondition.REFURB_GOOD,
            ItemCondition.REFURB_FAIR};
    private static final String[] NOTES = {
            "단순 변심으로 반품된 상품이에요. 포장만 개봉했어요",
            "박스에 눌림이 있지만 상품은 새것과 같아요",
            "배송 중 파손으로 반품된 상품을 검수했어요. 기능은 정상이에요",
            "오른쪽 옆면에 작은 흠집이 있어요",
            "불량으로 반품된 상품을 점검·수리했어요. 외관 흠집이 있을 수 있어요",
            "전시 상품이었어요. 구성품은 모두 있어요"};

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "리퍼마켓";
    }

    @Override
    public void seed(SeedContext ctx) {
        Long outlet = jdbc.queryForObject("select id from seller where name = '양팡 리퍼마켓'", Long.class);
        Long maxId = jdbc.queryForObject("select coalesce(max(id), 0) from vendor_item", Long.class);
        long id = maxId == null ? 0 : maxId;
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        int made = 0;
        for (SeedContext.Offer o : ctx.offers) {
            if (made >= 60) break;
            if (!"YANGPANG".equals(o.deliveryType()) || o.salePrice() < 5_000 || !ctx.chance(0.08)) continue;
            Long itemId = jdbc.queryForObject("select item_id from vendor_item where id = ?", Long.class, o.id());
            ItemCondition grade = GRADES[ctx.between(0, GRADES.length - 1)];
            String note = grade == ItemCondition.REFURB_BEST ? NOTES[ctx.between(0, 1)]
                    : grade == ItemCondition.REFURB_GOOD ? NOTES[ctx.between(2, 3)] : NOTES[ctx.between(4, 5)];
            long price = Math.round(o.salePrice() * grade.priceRate() / 10.0) * 10;
            Instant created = now.minus(Duration.ofHours(ctx.between(1, 24 * 30)));
            rows.add(new Object[]{++id, itemId, outlet, o.salePrice(), price, ctx.between(1, 3), "YANGPANG", 1,
                    Timestamp.from(created), Timestamp.from(created), grade.name(), note});
            made++;
        }
        jdbc.batchUpdate("""
                insert into vendor_item(id, item_id, seller_id, list_price, sale_price, stock, delivery_type, max_per_order,
                                        created_at, updated_at, item_condition, refurb_note, is_winner)
                values (?,?,?,?,?,?,?,?,?,?,?,?, false)""", rows);
        jdbc.execute("select setval(pg_get_serial_sequence('vendor_item', 'id'), (select max(id) from vendor_item))");
    }
}
