package com.yangpang.domain.seller;

import com.yangpang.domain.catalog.ProductSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 판매자 점수 (M4 ⑥). 시뮬레이션 날짜가 바뀌면 하루 한 번 실적으로 다시 계산해 seller.score(아이템 위너 15%)에 반영한다.
 * <pre>
 * 점수(0~100) = 리뷰 평점 40 + 판매자 귀책 반품·교환 35 + 문의 답변 25
 *   리뷰 평점   : 최근 90일 리뷰 평균을 리뷰 5개분의 4.0점과 섞어(적은 리뷰에 휘둘리지 않게) 1점=0, 5점=40
 *   반품·교환   : 최근 30일 판매자 귀책 반품·교환 상품 수 / 배송완료 상품 수(최소 20). 0%=35, 10% 이상=0
 *   문의 답변   : 최근 30일, 위너인 상품의 문의 중 48시간 안에 내가 답한 비율 (문의가 없으면 만점)
 * 등급: 85 이상 우수 · 60 이상 보통 · 40 이상 주의 · 40 미만 위험(시스템 경고, 7일에 한 번)
 * </pre>
 * 양팡 직매입(DIRECT)은 점수 계산·제재에서 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerScoreService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final double WARNING_BELOW = 40;

    /** 점수와 근거 */
    public record Score(double score, Double ratingAvg, int reviewCount, int delivered, int faultClaims, int inquiries, int answered48h) {
        public double ratingPart() {
            return SellerScoreService.ratingPart(ratingAvg == null ? 0 : ratingAvg * reviewCount, reviewCount);
        }

        public double claimPart() {
            return SellerScoreService.claimPart(delivered, faultClaims);
        }

        public double inquiryPart() {
            return SellerScoreService.inquiryPart(inquiries, answered48h);
        }

        public double claimRatePercent() {
            return delivered == 0 ? 0 : faultClaims * 100.0 / delivered;
        }

        public String grade() {
            return SellerScoreService.grade(score);
        }
    }

    private final JdbcTemplate jdbc;
    private final ProductSummaryService summaryService;
    private final SanctionService sanctionService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final Clock clock;

    // ---------------------------------------------------------------- 계산 규칙 (단위 테스트 대상)

    static double ratingPart(double ratingSum, int count) {
        double adjusted = (ratingSum + 4.0 * 5) / (count + 5);
        return 40 * Math.max(0, Math.min(1, (adjusted - 1) / 4));
    }

    static double claimPart(int delivered, int faultClaims) {
        double rate = (double) faultClaims / Math.max(delivered, 20);
        return 35 * Math.max(0, 1 - rate * 10);
    }

    static double inquiryPart(int inquiries, int answered) {
        return inquiries == 0 ? 25 : 25.0 * Math.min(answered, inquiries) / inquiries;
    }

    public static double total(double ratingSum, int reviews, int delivered, int faultClaims, int inquiries, int answered) {
        return Math.round((ratingPart(ratingSum, reviews) + claimPart(delivered, faultClaims) + inquiryPart(inquiries, answered)) * 10) / 10.0;
    }

    public static String grade(double score) {
        return score >= 85 ? "우수" : score >= 60 ? "보통" : score >= WARNING_BELOW ? "주의" : "위험";
    }

    // ---------------------------------------------------------------- 계산·저장

    /** 시뮬레이터: 오늘(시뮬레이션 날짜) 점수가 없으면 계산. @return 계산한 판매자 수 */
    public int recomputeIfStale() {
        Boolean done = jdbc.queryForObject("select exists (select 1 from seller_score_daily where day = ?)", Boolean.class, Date.valueOf(today()));
        return Boolean.TRUE.equals(done) ? 0 : recomputeAll();
    }

    @Transactional
    public int recomputeAll() {
        Timestamp now = Timestamp.from(clock.instant());
        LocalDate day = today();
        List<Object[]> rows = jdbc.query("""
                with s as (select id from seller where status in ('ACTIVE', 'SUSPENDED') and seller_type <> 'DIRECT'),
                rv as (select oi.seller_id, sum(r.rating) as total, count(*) as n from review r join order_item oi on oi.id = r.order_item_id
                       where r.created_at >= cast(? as timestamptz) - interval '90 days' group by 1),
                dl as (select seller_id, count(*) as n from order_item where delivered_at >= cast(? as timestamptz) - interval '30 days' group by 1),
                cl as (select oi.seller_id, count(*) as n from claim c join claim_item ci on ci.claim_id = c.id join order_item oi on oi.id = ci.order_item_id
                       where c.fault = 'SELLER' and c.claim_type in ('RETURN', 'EXCHANGE') and c.requested_at >= cast(? as timestamptz) - interval '30 days' group by 1),
                w as (select distinct i.product_id, vi.seller_id from vendor_item vi join item i on i.id = vi.item_id where vi.is_winner),
                iq as (select w.seller_id, count(*) as n,
                              count(*) filter (where q.answer_seller_id = w.seller_id and q.answered_at <= q.created_at + interval '48 hours') as a
                       from product_inquiry q join w on w.product_id = q.product_id
                       where q.created_at >= cast(? as timestamptz) - interval '30 days' and q.created_at <= cast(? as timestamptz) - interval '48 hours'
                       group by 1)
                select s.id, coalesce(rv.total, 0), coalesce(rv.n, 0), coalesce(dl.n, 0), coalesce(cl.n, 0), coalesce(iq.n, 0), coalesce(iq.a, 0)
                from s left join rv on rv.seller_id = s.id left join dl on dl.seller_id = s.id left join cl on cl.seller_id = s.id
                left join iq on iq.seller_id = s.id order by s.id""",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getDouble(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)},
                now, now, now, now, now);
        for (Object[] r : rows) {
            long sellerId = (Long) r[0];
            double sum = (Double) r[1];
            int reviews = (Integer) r[2], delivered = (Integer) r[3], claims = (Integer) r[4], inq = (Integer) r[5], ans = (Integer) r[6];
            double score = total(sum, reviews, delivered, claims, inq, ans);
            jdbc.update("""
                    insert into seller_score_daily(seller_id, day, score, rating_avg, review_count, delivered, seller_fault_claims, inquiries, answered_48h)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (seller_id, day) do update set score = excluded.score, rating_avg = excluded.rating_avg,
                        review_count = excluded.review_count, delivered = excluded.delivered, seller_fault_claims = excluded.seller_fault_claims,
                        inquiries = excluded.inquiries, answered_48h = excluded.answered_48h""",
                    sellerId, Date.valueOf(day), score, reviews == 0 ? null : Math.round(sum / reviews * 100) / 100.0, reviews, delivered, claims, inq, ans);
            jdbc.update("update seller set score = ? where id = ?", (int) Math.round(score), sellerId);
            long warnBelow = policyService.get(com.yangpang.domain.policy.Policy.SELLER_WARNING_SCORE);
            if (score < warnBelow && !sanctionService.recently(sellerId, SanctionService.Type.WARNING, 7)) {
                sanctionService.warn(sellerId, "판매자 점수 " + score + "점 (" + warnBelow + "점 미만): 반품·교환, 문의 답변, 리뷰 평점을 확인해 주세요", "SYSTEM");
            }
        }
        summaryService.refreshAll(); // 점수가 아이템 위너(15%)에 들어가므로 다시 고른다
        log.info("[판매자 점수] {} 기준 {}명 계산", day, rows.size());
        return rows.size();
    }

    @Transactional(readOnly = true)
    public Optional<Score> latest(Long sellerId) {
        return jdbc.query("""
                select score, rating_avg, review_count, delivered, seller_fault_claims, inquiries, answered_48h
                from seller_score_daily where seller_id = ? order by day desc limit 1""",
                rs -> rs.next() ? Optional.of(new Score(rs.getDouble(1), rs.getObject(2) == null ? null : rs.getDouble(2),
                        rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7))) : Optional.empty(), sellerId);
    }

    /** 최근 n일 점수 추이 (오래된 날부터) */
    @Transactional(readOnly = true)
    public List<double[]> trend(Long sellerId, int days) {
        return jdbc.query("""
                select extract(epoch from day)::bigint, score from seller_score_daily where seller_id = ? and day > ? order by day""",
                (rs, n) -> new double[]{rs.getLong(1), rs.getDouble(2)}, sellerId, Date.valueOf(today().minusDays(days)));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }
}
