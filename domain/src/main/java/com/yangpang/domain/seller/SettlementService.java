package com.yangpang.domain.seller;

import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 판매자 정산 (M4 ⑥, 모의 지급).
 * <pre>
 * 정산 단위   : 판매자 x 주(월~일, KST). 구매확정된 날이 속한 주에 넣는다
 * 판매금액    : 구매확정 상품의 단가 x 수량 (고객 쿠폰·캐시·정기배송 할인은 양팡 부담이라 빼지 않음)
 * 수수료      : 판매금액의 10%, 판매자양팡(물류 대행) 상품은 물류 대행비 5% 더함 (원 단위 내림)
 * 광고비      : 그 주에 쓴 검색 광고비
 * 지급액      : 판매금액 - 수수료 - 광고비 (모자라면 음수로 표시, 실제 청구 없음)
 * 지급일      : 주가 끝난 다음 월요일 + 7일. 지급일이 되면 시뮬레이터가 '지급 완료'로
 * </pre>
 * 이미 지급된 주에 늦게 확정된 상품(시뮬레이터가 지난 시각으로 처리한 경우)은 지난주 정산에 넣는다.
 * 양팡 직매입(DIRECT)은 정산하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final int COMMISSION_PERCENT = 10;
    public static final int FULFILLMENT_PERCENT = 5;
    public static final int PAY_AFTER_DAYS = 7;

    public record Row(Long id, LocalDate periodStart, LocalDate periodEnd, int itemCount, long salesAmount, long commission,
                      long adCost, long payout, String status, LocalDate payDate) {
        public boolean paid() {
            return "PAID".equals(status);
        }

        public LocalDate lastDay() {
            return periodEnd.minusDays(1);
        }
    }

    /** 아직 정산에 안 들어간 이번 주 예상치 */
    public record Estimate(int itemCount, long salesAmount, long commission, long adCost) {
        public long payout() {
            return salesAmount - commission - adCost;
        }
    }

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final Clock clock;

    /** 한 줄 수수료: 판매금액의 10% (+판매자양팡 5%), 원 단위 내림 */
    public static long commission(long amount, boolean fulfillment) {
        return amount * (COMMISSION_PERCENT + (fulfillment ? FULFILLMENT_PERCENT : 0)) / 100;
    }

    /** 관리자 설정 값 (지급 전 정산을 다시 계산할 때도 지금 값을 쓴다) */
    public long commissionPercent() {
        return policyService.get(com.yangpang.domain.policy.Policy.SELLER_COMMISSION_PERCENT);
    }

    public long fulfillmentPercent() {
        return policyService.get(com.yangpang.domain.policy.Policy.FULFILLMENT_FEE_PERCENT);
    }

    public long payAfterDays() {
        return policyService.get(com.yangpang.domain.policy.Policy.SETTLEMENT_PAY_AFTER_DAYS);
    }

    public static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 시뮬레이터: 지난주까지 구매확정된 미정산 상품을 주별 정산에 넣고, 지급일이 된 정산을 지급 완료로.
     * @return 새로 만든 정산 수 + 지급한 정산 수
     */
    @Transactional
    public int settleDue() {
        LocalDate thisWeek = weekStart(today());
        Timestamp cutoff = Timestamp.from(thisWeek.atStartOfDay(KST).toInstant());
        // (판매자, 주): 미정산 구매확정 상품이 있거나, 정산이 없는 주에 광고비를 쓴 경우
        List<Object[]> weeks = jdbc.query("""
                select oi.seller_id, date_trunc('week', oi.confirmed_at at time zone 'Asia/Seoul')::date as wk
                from order_item oi join seller s on s.id = oi.seller_id
                where oi.status = 'CONFIRMED' and oi.settlement_id is null and s.seller_type <> 'DIRECT' and oi.confirmed_at < ?
                group by 1, 2
                union
                select c.seller_id, date_trunc('week', d.day)::date
                from ad_stat_daily d join ad_campaign c on c.id = d.campaign_id join seller s on s.id = c.seller_id
                where d.day < ? and d.cost > 0 and s.seller_type <> 'DIRECT'
                  and not exists (select 1 from settlement st where st.seller_id = c.seller_id and st.period_start = date_trunc('week', d.day)::date)
                group by 1, 2
                order by 2, 1""", (rs, n) -> new Object[]{rs.getLong(1), rs.getDate(2).toLocalDate()}, cutoff, Date.valueOf(thisWeek));
        // 지급 전 정산에 들어간 뒤 구매확정이 풀린 상품(운영자 처리 등)은 빼고 다시 계산
        for (Long id : jdbc.queryForList("""
                update order_item oi set settlement_id = null from settlement st
                where st.id = oi.settlement_id and st.status = 'SCHEDULED' and oi.status <> 'CONFIRMED'
                returning st.id""", Long.class).stream().distinct().toList()) {
            recompute(id);
        }
        int created = 0;
        for (Object[] w : weeks) {
            long sellerId = (Long) w[0];
            LocalDate start = (LocalDate) w[1];
            Long id = find(sellerId, start);
            if (id == null) {
                id = create(sellerId, start);
                created++;
            } else if (isPaid(id)) {
                // 이미 지급한 주에 늦게 들어온 상품 → 지난주 정산으로. 그것도 지급됐으면 다음 주 정산 때까지 둔다
                LocalDate last = thisWeek.minusWeeks(1);
                id = find(sellerId, last);
                if (id == null) {
                    id = create(sellerId, last);
                    created++;
                } else if (isPaid(id)) {
                    continue;
                }
            }
            jdbc.update("""
                    update order_item set settlement_id = ? where seller_id = ? and status = 'CONFIRMED' and settlement_id is null
                    and confirmed_at >= ? and confirmed_at < ?""", id, sellerId,
                    Timestamp.from(start.atStartOfDay(KST).toInstant()), Timestamp.from(start.plusWeeks(1).atStartOfDay(KST).toInstant()));
            recompute(id);
        }
        // 지급 전 정산은 매번 다시 계산 (운영자가 수수료율을 바꾸면 다음 실행부터 반영)
        for (Long id : jdbc.queryForList("select id from settlement where status = 'SCHEDULED'", Long.class)) recompute(id);
        int paid = payDue();
        if (created + paid > 0) log.info("[정산] 새 정산 {}건, 지급 {}건", created, paid);
        return created + paid;
    }

    private Long find(long sellerId, LocalDate start) {
        return jdbc.query("select id from settlement where seller_id = ? and period_start = ?",
                rs -> rs.next() ? rs.getLong(1) : null, sellerId, Date.valueOf(start));
    }

    private boolean isPaid(Long id) {
        return "PAID".equals(jdbc.queryForObject("select status from settlement where id = ?", String.class, id));
    }

    private Long create(long sellerId, LocalDate start) {
        return jdbc.queryForObject("""
                insert into settlement(seller_id, period_start, period_end, pay_date, created_at) values (?, ?, ?, ?, ?) returning id""",
                Long.class, sellerId, Date.valueOf(start), Date.valueOf(start.plusWeeks(1)),
                Date.valueOf(start.plusWeeks(1).plusDays(payAfterDays())), Timestamp.from(clock.instant()));
    }

    /** 지급 전 정산의 합계를 다시 계산 */
    private void recompute(Long id) {
        jdbc.update("""
                update settlement s set item_count = x.n, sales_amount = x.sales, commission = x.comm, ad_cost = coalesce(ad.cost, 0),
                       payout = x.sales - x.comm - coalesce(ad.cost, 0)
                from (select count(*) as n, coalesce(sum(unit_price * quantity), 0) as sales,
                             coalesce(sum(floor(unit_price * quantity * (? + case when delivery_type = 'FULFILLMENT' then ? else 0 end) / 100.0)), 0)::bigint as comm
                      from order_item where settlement_id = ?) x
                left join lateral (select sum(d.cost) as cost from ad_stat_daily d join ad_campaign c on c.id = d.campaign_id
                                   join settlement s2 on s2.id = ? where c.seller_id = s2.seller_id
                                   and d.day >= s2.period_start and d.day < s2.period_end) ad on true
                where s.id = ? and s.status = 'SCHEDULED'""", commissionPercent(), fulfillmentPercent(), id, id, id);
    }

    private int payDue() {
        List<Object[]> due = jdbc.query("""
                update settlement set status = 'PAID', paid_at = ? where status = 'SCHEDULED' and pay_date <= ?
                returning id, seller_id, payout, period_start""",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getDate(4).toLocalDate()},
                Timestamp.from(clock.instant()), Date.valueOf(today()));
        LocalDate recent = today().minusDays(7);
        for (Object[] d : due) {
            // 처음 켰을 때 지난 1년치가 한꺼번에 지급되므로, 알림은 지급일이 최근 7일인 것만
            if (((LocalDate) d[3]).plusWeeks(2).isBefore(recent)) continue;
            for (Long memberId : jdbc.queryForList("select member_id from seller_member where seller_id = ?", Long.class, d[1])) {
                notificationService.notify(memberId, Notification.Type.ORDER, "정산금이 지급되었어요 (모의)",
                        d[3] + " 주 정산 · " + String.format("%,d", (Long) d[2]) + "원", "/seller/settlements", null);
            }
        }
        return due.size();
    }

    // ---------------------------------------------------------------- 조회

    @Transactional(readOnly = true)
    public List<Row> list(Long sellerId, int limit) {
        return jdbc.query("""
                select id, period_start, period_end, item_count, sales_amount, commission, ad_cost, payout, status, pay_date
                from settlement where seller_id = ? order by period_start desc limit ?""", (rs, n) -> row(rs), sellerId, limit);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Row> one(Long sellerId, Long id) {
        return jdbc.query("""
                select id, period_start, period_end, item_count, sales_amount, commission, ad_cost, payout, status, pay_date
                from settlement where seller_id = ? and id = ?""", rs -> rs.next() ? java.util.Optional.of(row(rs)) : java.util.Optional.empty(), sellerId, id);
    }

    /** 이번 주(아직 정산 전) 구매확정 상품과 광고비로 본 예상치 */
    @Transactional(readOnly = true)
    public Estimate estimate(Long sellerId) {
        LocalDate thisWeek = weekStart(today());
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(unit_price * quantity), 0),
                       coalesce(sum(floor(unit_price * quantity * (? + case when delivery_type = 'FULFILLMENT' then ? else 0 end) / 100.0)), 0)::bigint,
                       (select coalesce(sum(d.cost), 0) from ad_stat_daily d join ad_campaign c on c.id = d.campaign_id
                        where c.seller_id = ? and d.day >= ?)
                from order_item where seller_id = ? and status = 'CONFIRMED' and settlement_id is null""",
                (rs, n) -> new Estimate(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                commissionPercent(), fulfillmentPercent(), sellerId, Date.valueOf(thisWeek), sellerId);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(), rs.getInt(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getDate(10).toLocalDate());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }
}
