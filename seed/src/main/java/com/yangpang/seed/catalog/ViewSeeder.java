package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 상품 조회 수 (M3 랭킹): 최근 30일 일별 조회 수. 많이 팔린 상품일수록 많이 보지만 무작위 편차를 크게 둬서
 * "많이 보는데 잘 안 사는" 상품과 "적게 보는데 잘 사는" 상품이 섞이게 한다 (전환율 지표 실험용).
 */
@Component
@Order(98)
@RequiredArgsConstructor
public class ViewSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "상품 조회 수";
    }

    @Override
    public void seed(SeedContext ctx) {
        jdbc.execute("select setseed(" + (ctx.props.randomSeed() % 1000) / 1000.0 + ")");
        jdbc.update("""
                insert into product_view_daily(product_id, day, views)
                select p.id, d::date,
                       greatest(0, round((2 + ln(2 + p.sales_count) * 3) * (0.2 + random() * 1.6) * (0.5 + random())))::int
                from product p cross join generate_series(current_date - 29, current_date, interval '1 day') d
                where p.status = 'ON_SALE'""");
    }
}
