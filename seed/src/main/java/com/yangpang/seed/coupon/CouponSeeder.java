package com.yangpang.seed.coupon;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.member.MemberSeeder;
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
 * 가짜 쿠폰·양팡캐시 (M2).
 * - 쿠폰 정의 12종 (전체·카테고리 상품 쿠폰, 장바구니 쿠폰, 가입 축하 쿠폰)
 * - 회원마다 받은 쿠폰 1~4장 (테스트 계정은 넉넉히)
 * - 캐시 적립 내역 (테스트 계정은 5,000원 이상)
 */
@Component
@Order(90)
@RequiredArgsConstructor
public class CouponSeeder implements Seeder {

    /** name, kind, type, value, max, min, 대분류 이름(null=전체), days, qty, downloadable, auto */
    private record Def(String name, String kind, String type, long value, Long max, long min, String top, int days,
                       Integer qty, boolean downloadable, String auto) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("가입 축하 3,000원 쿠폰", "CART", "AMOUNT", 3_000, null, 15_000, null, 30, null, false, "SIGNUP"),
            new Def("전 상품 5% 할인", "PRODUCT", "RATE", 5, 5_000L, 10_000, null, 14, null, true, null),
            new Def("장바구니 2,000원 할인", "CART", "AMOUNT", 2_000, null, 20_000, null, 14, null, true, null),
            new Def("장바구니 10% (최대 1만원)", "CART", "RATE", 10, 10_000L, 50_000, null, 7, 500, true, null),
            new Def("패션 15% 할인", "PRODUCT", "RATE", 15, 15_000L, 20_000, "패션의류/잡화", 14, null, true, null),
            new Def("뷰티 3,000원 할인", "PRODUCT", "AMOUNT", 3_000, null, 15_000, "뷰티", 14, null, true, null),
            new Def("주방용품 10% 할인", "PRODUCT", "RATE", 10, 8_000L, 10_000, "주방용품", 14, null, true, null),
            new Def("가전디지털 5만원 할인", "PRODUCT", "AMOUNT", 50_000, null, 500_000, "가전디지털", 30, 100, true, null),
            new Def("신선식품 2,000원 할인", "PRODUCT", "AMOUNT", 2_000, null, 10_000, "신선식품", 7, null, true, null),
            new Def("생활용품 1,000원 할인", "PRODUCT", "AMOUNT", 1_000, null, 5_000, "생활용품", 14, null, true, null),
            new Def("반려동물 12% 할인", "PRODUCT", "RATE", 12, 6_000L, 10_000, "반려동물용품", 14, null, true, null),
            new Def("도서 1,500원 할인", "PRODUCT", "AMOUNT", 1_500, null, 12_000, "도서/음반", 14, null, true, null));

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "쿠폰/양팡캐시";
    }

    @Override
    public void seed(SeedContext ctx) {
        Instant now = Instant.now();
        List<Object[]> coupons = new ArrayList<>();
        long id = 0;
        for (Def d : DEFS) {
            Long categoryId = d.top() == null ? null : jdbc.queryForObject(
                    "select id from category where depth = 1 and name = ?", Long.class, d.top());
            coupons.add(new Object[]{++id, d.name(), d.kind(), d.type(), d.value(), d.max(), d.min(), categoryId,
                    d.days(), d.qty(), d.downloadable(), d.auto(), Timestamp.from(now.minus(Duration.ofDays(30)))});
        }
        jdbc.batchUpdate("""
                insert into coupon(id, name, coupon_type, discount_type, discount_value, max_discount, min_order_amount,
                                   target_category_id, valid_days, total_quantity, downloadable, auto_issue, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)""", coupons);

        // 회원 쿠폰
        List<Object[]> memberCoupons = new ArrayList<>();
        long mcId = 0;
        List<Long> downloadable = new ArrayList<>();
        for (int i = 2; i <= DEFS.size(); i++) downloadable.add((long) i);
        List<Long> members = new ArrayList<>(ctx.memberIds);
        for (long memberId : members) {
            List<Long> picks = memberId == MemberSeeder.NORMAL_TEST_MEMBER_ID
                    ? List.of(2L, 3L, 4L, 5L, 7L, 9L, 10L)       // 테스트 계정: 확인하기 좋게 넉넉히
                    : ctx.pickDistinct(downloadable, ctx.between(1, 4));
            for (long couponId : picks) {
                Instant issued = now.minus(Duration.ofDays(ctx.between(0, 5)));
                memberCoupons.add(new Object[]{++mcId, memberId, couponId, "AVAILABLE", Timestamp.from(issued),
                        Timestamp.from(issued.plus(Duration.ofDays(DEFS.get((int) couponId - 1).days())))});
            }
        }
        jdbc.batchUpdate("insert into member_coupon(id, member_id, coupon_id, status, issued_at, expires_at) values (?,?,?,?,?,?)",
                memberCoupons);
        jdbc.update("update coupon c set issued_count = (select count(*) from member_coupon m where m.coupon_id = c.id)");

        // 양팡캐시 적립 내역
        List<Object[]> ledger = new ArrayList<>();
        long lid = 0;
        String[] events = {"출석체크 이벤트 적립", "친구 초대 이벤트 적립", "첫 구매 감사 적립", "리뷰 이벤트 당첨"};
        for (long memberId : members) {
            int n = memberId == MemberSeeder.NORMAL_TEST_MEMBER_ID ? 4 : ctx.between(0, 3);
            for (int k = 0; k < n; k++) {
                long amount = memberId == MemberSeeder.NORMAL_TEST_MEMBER_ID ? 2_000 : ctx.between(1, 20) * 100L;
                Instant at = now.minus(Duration.ofDays(ctx.between(1, 200)));
                ledger.add(new Object[]{++lid, memberId, amount, "EARN_EVENT", ctx.pick(List.of(events)),
                        Timestamp.from(at.plus(Duration.ofDays(365))), Timestamp.from(at)});
            }
        }
        jdbc.batchUpdate("""
                insert into cash_ledger(id, member_id, amount, entry_type, description, expires_at, created_at)
                values (?,?,?,?,?,?,?)""", ledger);
    }
}
