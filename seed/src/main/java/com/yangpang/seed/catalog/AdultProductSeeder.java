package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 19세 이상 상품 (성인 인증 실험용, 모두 가상 상품). 식품 > 음료 아래에 "전통주·와인" 소분류를 만들고 8개를 양팡 직매입으로 넣는다.
 * 다른 시더처럼 id를 직접 정한다 (시퀀스는 시드가 끝날 때 맞춘다).
 * normal@example.com은 성인 인증을 해 둔다 (인증 전 화면은 plus@ 등으로 확인).
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class AdultProductSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    private static final Object[][] PRODUCTS = {
            {"누리양조 생막걸리 750ml", 4500L, 4900L},
            {"솔바람 전통 약주 375ml", 12900L, 14000L},
            {"한결 복분자주 375ml", 9800L, 11000L},
            {"다온 오미자 와인 750ml", 24000L, 28000L},
            {"모아 맑은 청주 300ml", 6500L, 6500L},
            {"온새미로 사과 증류주 375ml", 29000L, 32000L},
            {"바른 레드와인 750ml", 19900L, 25000L},
            {"새벽 화이트와인 750ml", 18500L, 22000L},
    };

    @Override
    public String name() {
        return "19세 이상 상품 (전통주·와인)";
    }

    @Override
    public void seed(SeedContext ctx) {
        Long drinks = jdbc.query("select c.id from category c join category p on p.id = c.parent_id where c.name = '음료' and p.name = '식품'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (drinks == null) return;
        Long leaf = jdbc.queryForObject("insert into category(id, parent_id, name, depth, sort_order) select (select max(id) + 1 from category), ?, '전통주·와인', depth + 1, 99 from category where id = ? returning id",
                Long.class, drinks, drinks);
        Long seller = jdbc.queryForObject("select id from seller where seller_type = 'DIRECT' order by id limit 1", Long.class);
        for (Object[] p : PRODUCTS) {
            String name = (String) p[0];
            long sale = (Long) p[1], list = (Long) p[2];
            Long pid = jdbc.queryForObject("""
                    insert into product(id, category_id, name, description_html, status, adult, created_at, updated_at, rating_avg, review_count, sales_count)
                    values ((select max(id) + 1 from product), ?, ?, ?, 'ON_SALE', true, now(), now(), 0, 0, ?) returning id""", Long.class, leaf, name,
                    "<p>연구용 가상 상품입니다. 실제로 판매하지 않습니다.</p><p><b>19세 미만 청소년은 구매할 수 없습니다.</b> 지나친 음주는 건강에 해롭습니다.</p>",
                    ctx.between(10, 300));
            Long itemId = jdbc.queryForObject("insert into item(id, product_id, sort_order) values ((select max(id) + 1 from item), ?, 0) returning id", Long.class, pid);
            Long vi = jdbc.queryForObject("""
                    insert into vendor_item(id, item_id, seller_id, list_price, sale_price, stock, delivery_type, max_per_order, is_winner, status, created_at, updated_at, item_condition)
                    values ((select max(id) + 1 from vendor_item), ?, ?, ?, ?, 60, 'YANGPANG', 6, true, 'ON_SALE', now(), now(), 'NEW') returning id""", Long.class, itemId, seller, list, sale);
            jdbc.update("insert into product_image(id, product_id, url, sort_order) values ((select coalesce(max(id), 0) + 1 from product_image), ?, ?, 1)", pid, "/images/products/" + pid + "/1.svg");
            jdbc.update("""
                    update product set rep_vendor_item_id = ?, rep_sale_price = ?, rep_list_price = ?, rep_delivery_type = 'YANGPANG', sold_out = false
                    where id = ?""", vi, sale, list, pid);
        }
        jdbc.update("update member set adult_verified_at = now(), birth_date = coalesce(birth_date, date '1990-05-05') where email = 'normal@example.com'");
    }
}
