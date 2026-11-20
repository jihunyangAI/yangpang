package com.yangpang.seed.member;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 정기배송 (M4 ④). 재구매 주기가 있는 카테고리(식품·생활용품 등)의 상품으로 정기배송을 만든다.
 * 회차 주문은 앱의 시뮬레이터가 배송 예정일 이틀 전에 만든다 (시드는 주문을 만들지 않음).
 * normal@example.com: 2건 (하나는 곧 자동 주문되도록 배송 예정일을 이틀 뒤로).
 */
@Component
@Order(102)
@RequiredArgsConstructor
public class SubscriptionSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "정기배송";
    }

    @Override
    public void seed(SeedContext ctx) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Timestamp now = Timestamp.from(Instant.now());
        // 정기배송할 만한 상품 옵션: 재구매 주기가 있는 카테고리, 새 상품, 재고 넉넉
        List<Long> offers = jdbc.queryForList("""
                select vi.id from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                join category_tree t on t.id = p.category_id join category tc on tc.id = t.top_id
                where tc.repurchase_days is not null and vi.item_condition = 'NEW' and vi.status = 'ON_SALE' and vi.stock >= 30 and vi.delivery_type <> 'GLOBAL'
                  and p.status = 'ON_SALE' and vi.is_winner
                order by vi.id""", Long.class);
        if (offers.isEmpty()) return;
        List<Map<String, Object>> members = jdbc.queryForList("""
                select m.id, m.email, (select a.id from address a where a.member_id = m.id order by a.is_default desc, a.id limit 1) as address_id
                from member m where m.role = 'USER' and exists (select 1 from address a where a.member_id = m.id)
                order by m.id""");
        int[] cycles = {1, 2, 2, 2, 4, 4};
        String[] methods = {"YANGPAY", "YANGPAY", "CARD", "PHONE"};
        for (Map<String, Object> m : members) {
            boolean normal = "normal@example.com".equals(m.get("email"));
            int count = normal ? 2 : (ctx.random.nextDouble() < 0.06 ? ctx.between(1, 2) : 0);
            List<Long> picks = ctx.pickDistinct(offers, Math.min(count, offers.size()));
            for (int k = 0; k < picks.size(); k++) {
                Long vi = picks.get(k);
                int cycle = normal ? (k == 0 ? 2 : 4) : cycles[ctx.random.nextInt(cycles.length)];
                LocalDate next = normal ? today.plusDays(k == 0 ? 2 : 10) : today.plusDays(ctx.between(3, cycle * 7));
                jdbc.update("""
                        insert into subscription(member_id, vendor_item_id, product_id, quantity, cycle_weeks, address_id, method, status,
                                                 next_delivery_date, rounds, skipped, created_at)
                        select ?, vi.id, i.product_id, ?, ?, ?, ?, 'ACTIVE', ?, 0, 0, ?
                        from vendor_item vi join item i on i.id = vi.item_id where vi.id = ?""",
                        m.get("id"), normal ? 1 : ctx.between(1, 3), cycle, m.get("address_id"),
                        normal ? "YANGPAY" : methods[ctx.random.nextInt(methods.length)], Date.valueOf(next), now, vi);
            }
        }
    }
}
