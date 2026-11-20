package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.member.FakePerson;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MVP 판매자 5곳: 양팡 직매입 1 / 판매자양팡(풀필먼트) 2 / 판매자배송 2.
 * 사업자번호·연락처는 형식만 맞춘 가짜 값.
 */
@Component
@Order(30)
@RequiredArgsConstructor
public class SellerSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    private record Def(String name, String type, long fee, Long freeThreshold, String status) {
        Def(String name, String type, long fee, Long freeThreshold) {
            this(name, type, fee, freeThreshold, "ACTIVE");
        }
    }

    private static final List<Def> SELLERS = List.of(
            new Def("양팡", "DIRECT", 3_000, null),
            new Def("한결상사", "FULFILLMENT", 3_000, null),
            new Def("다온리빙", "FULFILLMENT", 3_000, null),
            new Def("누리마켓", "MARKETPLACE", 3_000, 30_000L),
            new Def("소담공방", "MARKETPLACE", 2_500, null),
            // M3: 경쟁 오퍼용 판매자 + 입점 심사 대기 1곳
            new Def("바른생활몰", "MARKETPLACE", 3_000, 20_000L),
            new Def("온새미로", "FULFILLMENT", 3_000, null),
            new Def("모두의가게", "MARKETPLACE", 2_500, 25_000L),
            new Def("새싹상점", "MARKETPLACE", 3_000, null, "PENDING"),
            // M3: 반품 검수 상품을 다시 파는 양팡 직영 (리퍼마켓)
            new Def("양팡 리퍼마켓", "DIRECT", 3_000, null));

    @Override
    public String name() {
        return "판매자";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<Object[]> rows = new ArrayList<>();
        long id = 0;
        for (Def d : SELLERS) {
            id++;
            String businessNo = String.format("000-00-%05d", id);       // 가짜 사업자번호
            String phone = String.format("010-0000-%04d", 9000 + id);    // 미사용 대역
            rows.add(new Object[]{id, d.name(), d.type(), businessNo, FakePerson.name(ctx),
                    "서울특별시 가상구 연구로 " + (10 + id), phone, d.fee(), d.freeThreshold(), ctx.between(85, 100), d.status()});
            if (!"ACTIVE".equals(d.status())) continue;
            ctx.sellers.add(new SeedContext.SellerRef(id, d.type(), d.fee(), d.freeThreshold()));
        }
        jdbc.batchUpdate("""
                insert into seller(id, name, seller_type, business_no, ceo_name, business_address, cs_phone,
                                   shipping_fee, free_shipping_threshold, score, status)
                values (?,?,?,?,?,?,?,?,?,?,?)""", rows);
    }
}
