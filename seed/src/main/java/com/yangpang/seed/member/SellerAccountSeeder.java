package com.yangpang.seed.member;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 판매자 계정 연결 (M3): seller@example.com → 누리마켓(판매자배송), 심사 대기 판매자에는 가짜 회원 1명 */
@Component
@Order(57)
@RequiredArgsConstructor
public class SellerAccountSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "판매자 계정";
    }

    @Override
    public void seed(SeedContext ctx) {
        jdbc.update("insert into seller_member(seller_id, member_id) select id, ? from seller where name = '누리마켓'",
                MemberSeeder.SELLER_TEST_MEMBER_ID);
        // 심사 대기 판매자: 일반 가짜 회원 하나가 신청한 것으로
        jdbc.update("""
                insert into seller_member(seller_id, member_id)
                select s.id, (select max(id) from member where role = 'USER' and grade = 'NORMAL') from seller s where s.status = 'PENDING'""");
    }
}
