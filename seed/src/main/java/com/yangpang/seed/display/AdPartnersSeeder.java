package com.yangpang.seed.display;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 검색 광고 · 파트너스 (M4 ③).
 * 광고: 판매자가 여럿인 소분류 6개를 골라 분류마다 판매자 2~3명이 소분류 이름(예: "텐트")으로 경쟁 입찰. 지난 7일 성과도 만든다.
 * 파트너스: normal@example.com이 링크 3개를 만들고, 다른 회원의 최근 주문 몇 건이 그 링크로 들어온 것으로 둔다
 * (실적 확정·취소는 앱의 시뮬레이터가 주문 상태를 보고 처리).
 */
@Component
@Order(101)
@RequiredArgsConstructor
public class AdPartnersSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "검색 광고·파트너스";
    }

    @Override
    public void seed(SeedContext ctx) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.execute("select setseed(" + (ctx.props.randomSeed() % 997) / 1000.0 + ")");

        // ---- 광고: 판매자가 많은 소분류 6개 ----
        List<Map<String, Object>> leaves = jdbc.queryForList("""
                select c.id, lower(c.name) as name from category c
                join product p on p.category_id = c.id join item i on i.product_id = p.id join vendor_item vi on vi.item_id = i.id
                where p.status = 'ON_SALE' and vi.item_condition = 'NEW' and length(c.name) <= 20
                group by c.id, c.name having count(distinct vi.seller_id) >= 2
                order by count(distinct vi.seller_id) desc, c.id limit 6""");
        for (Map<String, Object> leaf : leaves) {
            Long leafId = ((Number) leaf.get("id")).longValue();
            String keyword = ((String) leaf.get("name")).replaceAll("\\s+", " ").trim();
            // 판매자별로 그 분류에서 가장 많이 팔린 자기 상품 하나
            List<Map<String, Object>> picks = jdbc.queryForList("""
                    select distinct on (vi.seller_id) vi.seller_id, p.id as product_id
                    from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                    where p.category_id = ? and p.status = 'ON_SALE' and vi.item_condition = 'NEW'
                    order by vi.seller_id, p.sales_count desc, p.id""", leafId);
            int n = Math.min(picks.size(), ctx.between(2, 3));
            for (Map<String, Object> pick : ctx.pickDistinct(picks, n)) {
                Long cid = jdbc.queryForObject("""
                        insert into ad_campaign(seller_id, product_id, daily_budget, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?) returning id""", Long.class,
                        pick.get("seller_id"), pick.get("product_id"), 1000L * ctx.between(10, 50), now);
                jdbc.update("insert into ad_keyword(campaign_id, keyword, bid) values (?, ?, ?)", cid, keyword, 10 * ctx.between(15, 90));
            }
        }
        // 테스트 판매자(seller@example.com)도 광고 하나는 갖도록
        jdbc.update("""
                insert into ad_campaign(seller_id, product_id, daily_budget, status, created_at)
                select sm.seller_id, x.pid, 20000, 'ACTIVE', ?
                from seller_member sm join member m on m.id = sm.member_id
                cross join lateral (select p.id as pid from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                                    where vi.seller_id = sm.seller_id and p.status = 'ON_SALE' and vi.item_condition = 'NEW'
                                      and not exists (select 1 from ad_campaign a where a.seller_id = sm.seller_id)
                                    order by p.sales_count desc limit 1) x
                where m.email = 'seller@example.com'""", now);
        jdbc.update("""
                insert into ad_keyword(campaign_id, keyword, bid)
                select a.id, lower(c.name), 300 from ad_campaign a join product p on p.id = a.product_id join category c on c.id = p.category_id
                where not exists (select 1 from ad_keyword k where k.campaign_id = a.id)""");

        // 지난 7일 성과
        jdbc.update("""
                insert into ad_stat_daily(campaign_id, keyword_id, day, impressions, clicks, cost, conversions, sales)
                select k.campaign_id, k.id, d::date, a.imp, b.clk, round(b.clk * k.bid * (0.55 + random() * 0.4))::bigint, c.conv,
                       c.conv * coalesce(p.rep_sale_price, 10000)
                from ad_keyword k join ad_campaign ac on ac.id = k.campaign_id join product p on p.id = ac.product_id
                cross join generate_series(current_date - 7, current_date - 1, interval '1 day') d
                cross join lateral (select (40 + random() * 360)::int as imp) a
                cross join lateral (select round(a.imp * (0.01 + random() * 0.05))::int as clk) b
                cross join lateral (select round(b.clk * random() * 0.15)::int as conv) c""");

        // ---- 파트너스 ----
        Long normal = jdbc.query("select id from member where email = 'normal@example.com'", rs -> rs.next() ? rs.getLong(1) : null);
        if (normal == null) return;
        List<Long> products = jdbc.queryForList("""
                select id from product where status = 'ON_SALE' and not sold_out order by sales_count desc, id limit 3""", Long.class);
        String[] codes = {"demoA1x9", "demoB2k7", "demoC3m5"};
        for (int i = 0; i < products.size(); i++) {
            jdbc.update("insert into affiliate_link(member_id, product_id, code, clicks, created_at) values (?, ?, ?, ?, ?)",
                    normal, products.get(i), codes[i], ctx.between(15, 90), now);
        }
        // 다른 회원의 최근 30일 결제 주문 8건을 링크에 연결
        jdbc.update("""
                update orders o set affiliate_link_id = (select id from affiliate_link where member_id = ? order by random() limit 1)
                where o.id in (select o2.id from orders o2 where o2.member_id <> ? and o2.ordered_at >= now() - interval '30 days'
                                 and o2.status not in ('PENDING_PAYMENT', 'PAYMENT_FAILED') order by random() limit 8)""", normal, normal);
        jdbc.update("""
                insert into affiliate_earning(link_id, order_id, base_amount, amount, status, created_at)
                select o.affiliate_link_id, o.id, s.base, floor(s.base * 0.03), 'PENDING', o.ordered_at
                from orders o cross join lateral (select coalesce(sum(line_amount - discount_amount), 0) as base from order_item where order_id = o.id) s
                where o.affiliate_link_id is not null""");
    }
}
