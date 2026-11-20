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
import java.util.List;

/**
 * 양팡플러스 멤버십 (M3): 플러스 회원마다 가입일·이번 기간·지난 청구 내역(첫 달 무료 + 월 회비 모의 청구).
 * 일부(10%)는 해지 예정 상태로 만든다.
 */
@Component
@Order(56)
@RequiredArgsConstructor
public class MembershipSeeder implements Seeder {

    private static final long FEE = 7_890;
    private static final Duration PERIOD = Duration.ofDays(30);

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "양팡플러스 멤버십";
    }

    @Override
    public void seed(SeedContext ctx) {
        Instant now = Instant.now();
        List<Object[]> memberships = new ArrayList<>();
        List<Object[]> billings = new ArrayList<>();
        long id = 0, bid = 0;
        for (long memberId : ctx.plusMemberIds) {
            id++;
            Instant started = now.minus(Duration.ofDays(ctx.between(3, 400)));
            int periods = (int) (Duration.between(started, now).toDays() / 30);
            Instant periodStart = started.plus(PERIOD.multipliedBy(periods));
            Instant periodEnd = periodStart.plus(PERIOD);
            boolean canceled = memberId != MemberSeeder.PLUS_TEST_MEMBER_ID && ctx.chance(0.1);
            memberships.add(new Object[]{id, memberId, canceled ? "CANCELED" : "ACTIVE", FEE, Timestamp.from(started),
                    Timestamp.from(periodStart), Timestamp.from(periodEnd),
                    canceled ? Timestamp.from(now.minus(Duration.ofDays(ctx.between(0, 5)))) : null});
            for (int k = 0; k <= periods; k++) {
                Instant s = started.plus(PERIOD.multipliedBy(k));
                billings.add(new Object[]{++bid, id, k == 0 ? 0L : FEE, k == 0 ? "FREE_TRIAL" : "MONTHLY",
                        Timestamp.from(s), Timestamp.from(s.plus(PERIOD)), Timestamp.from(s)});
            }
        }
        jdbc.batchUpdate("""
                insert into membership(id, member_id, status, monthly_fee, started_at, current_period_start, current_period_end, canceled_at)
                values (?,?,?,?,?,?,?,?)""", memberships);
        jdbc.batchUpdate("""
                insert into membership_billing(id, membership_id, amount, kind, period_start, period_end, billed_at)
                values (?,?,?,?,?,?,?)""", billings);
    }
}
