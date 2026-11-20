package com.yangpang.seed.display;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 기획전 (M3): 대분류별 기획전 5개. 상품이 많은 소분류 3개를 구역으로, 구역마다 많이 팔린 상품 10개.
 * 같은 분류의 다운로드 쿠폰이 있으면 전용 쿠폰으로 연결.
 */
@Component
@Order(95)
@RequiredArgsConstructor
public class ExhibitionSeeder implements Seeder {

    private record Def(String slug, String title, String subtitle, String theme, String topCategory, String coupon, int startDaysAgo,
                       int days) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("winter-camping", "겨울 캠핑 준비전", "텐트부터 캠핑의자까지, 따뜻한 겨울 캠핑", "DARK", "스포츠/레저", null, 5, 30),
            new Def("kitchen-refresh", "주방 새단장 기획전", "연말 집들이 전에 바꾸는 주방용품", "BRAND", "주방용품", "주방용품 10% 할인", 3, 21),
            new Def("pet-health", "반려동물 건강 주간", "사료·간식·영양제 모아보기", "FRESH", "반려동물용품", "반려동물 12% 할인", 1, 14),
            new Def("digital-week", "디지털 위크", "이번 주만, 가전·디지털 인기 상품", "PLUS", "가전디지털", "가전디지털 5만원 할인", 2, 9),
            new Def("fresh-table", "제철 신선 밥상", "새벽배송으로 받는 제철 식재료", "SALE", "신선식품", "신선식품 2,000원 할인", 0, 10));

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "기획전";
    }

    @Override
    public void seed(SeedContext ctx) {
        Instant now = Instant.now();
        int sort = 0;
        for (Def d : DEFS) {
            List<Long> coupon = d.coupon() == null ? List.of()
                    : jdbc.queryForList("select id from coupon where name = ?", Long.class, d.coupon());
            Instant start = now.minus(Duration.ofDays(d.startDaysAgo()));
            Long id = jdbc.queryForObject("""
                    insert into exhibition(slug, title, subtitle, theme, coupon_id, starts_at, ends_at, sort_order)
                    values (?,?,?,?,?,?,?,?) returning id""", Long.class,
                    d.slug(), d.title(), d.subtitle(), d.theme(), coupon.isEmpty() ? null : coupon.getFirst(),
                    Timestamp.from(start), Timestamp.from(start.plus(Duration.ofDays(d.days()))), sort += 10);
            // 대분류 아래 상품이 많은 소분류 3개 → 구역, 구역마다 판매량 상위 10개
            jdbc.update("""
                    insert into exhibition_product(exhibition_id, product_id, section, sort_order)
                    select ?, ranked.id, ranked.leaf_name, ranked.rn + ranked.leaf_rank * 100
                    from (
                        select p.id, c.name as leaf_name,
                               row_number() over (partition by c.id order by p.sales_count desc, p.id) as rn,
                               dense_rank() over (order by leaf.cnt desc, c.id) as leaf_rank
                        from product p join category c on c.id = p.category_id
                        join (select category_id, count(*) cnt from product where status = 'ON_SALE' and not sold_out group by category_id) leaf
                          on leaf.category_id = c.id
                        where p.status = 'ON_SALE' and not p.sold_out
                          and c.id in (select x.id from category x
                                       left join category x1 on x1.id = x.parent_id
                                       left join category x2 on x2.id = x1.parent_id
                                       join category t on t.id = coalesce(x2.id, x1.id) and t.parent_id is null and t.name = ?)
                    ) ranked
                    where ranked.rn <= 10 and ranked.leaf_rank <= 3""", id, d.topCategory());
        }
    }
}
