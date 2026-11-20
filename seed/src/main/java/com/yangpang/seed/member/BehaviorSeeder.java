package com.yangpang.seed.member;

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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 회원 행동 로그 (M4 ②): 최근 30일 둘러보기.
 * 회원마다 관심 중분류 2~3개를 정해 두고, 둘러보기의 80%는 관심 분류에서, 20%는 아무 분류에서 한다.
 * 한 번 둘러볼 때 같은 분류의 인기 상품 3~7개를 보고, 가끔 담거나 찜한다 → "함께 본 상품"이 자연스럽게 생긴다.
 * 주문 이벤트는 실제 주문에서 옮긴다.
 */
@Component
@Order(99)
@RequiredArgsConstructor
public class BehaviorSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "회원 행동 로그";
    }

    @Override
    public void seed(SeedContext ctx) {
        // 카테고리를 새로 만들었으므로 소모품 재구매 주기를 다시 넣는다 (V19와 같은 값)
        jdbc.update("""
                update category set repurchase_days = case name
                    when '신선식품' then 7 when '식품' then 21 when '출산/유아동' then 21 when '생활용품' then 30
                    when '반려동물용품' then 30 when '헬스/건강식품' then 30 when '뷰티' then 45 end
                where parent_id is null""");

        Map<Long, List<Long>> byMid = new HashMap<>();
        jdbc.query("""
                select t.mid_id, p.id from product p join category_tree t on t.id = p.category_id
                where p.status = 'ON_SALE' order by t.mid_id, p.sales_count desc, p.id""",
                rs -> {
                    byMid.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
                });
        List<Long> mids = new ArrayList<>(byMid.keySet());
        mids.sort(null);
        if (mids.isEmpty()) return;

        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        for (Long memberId : ctx.memberIds) {
            List<Long> favorites = ctx.pickDistinct(mids, Math.min(mids.size(), ctx.between(2, 3)));
            int sessions = ctx.between(4, 12);
            for (int s = 0; s < sessions; s++) {
                Long mid = ctx.chance(0.8) ? ctx.pick(favorites) : ctx.pick(mids);
                List<Long> pool = byMid.get(mid);
                pool = pool.subList(0, Math.min(25, pool.size()));
                Instant at = now.minus(Duration.ofMinutes(ctx.between(10, 30 * 24 * 60)));
                int views = Math.min(pool.size(), ctx.between(3, 7));
                Set<Long> seen = new LinkedHashSet<>();
                while (seen.size() < views) {
                    // 인기 상품(목록 앞쪽)을 더 자주 본다
                    int idx = (int) Math.min(pool.size() - 1, Math.floor(Math.pow(ctx.random.nextDouble(), 2) * pool.size()));
                    seen.add(pool.get(idx));
                }
                int k = 0;
                for (Long pid : seen) {
                    rows.add(new Object[]{memberId, "VIEW", pid, Timestamp.from(at.plusSeconds(40L * k++))});
                }
                Long first = seen.iterator().next();
                if (ctx.chance(0.15)) rows.add(new Object[]{memberId, "CART", first, Timestamp.from(at.plusSeconds(40L * k))});
                if (ctx.chance(0.08)) rows.add(new Object[]{memberId, "WISH", first, Timestamp.from(at.plusSeconds(40L * k + 5))});
            }
        }
        jdbc.batchUpdate("insert into user_event(member_id, event_type, product_id, created_at) values (?,?,?,?)", rows);

        // 주문 이벤트는 실제 결제된 주문에서
        jdbc.update("""
                insert into user_event(member_id, event_type, product_id, created_at)
                select distinct o.member_id, 'ORDER', oi.product_id, o.ordered_at
                from order_item oi join orders o on o.id = oi.order_id
                where oi.status not in ('PENDING_PAYMENT', 'PAYMENT_FAILED') and o.ordered_at >= now() - interval '60 days'""");
    }
}
