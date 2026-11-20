package com.yangpang.domain.partners;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 양팡 파트너스 (M4 ③, 기획서 §22). 연구용 모의 — 실제 지급 없음.
 * 회원이 상품 공유 링크를 만들고, 그 링크로 들어온 다른 회원이 24시간 안에 주문하면(결제 완료) 주문 상품 금액의 3%를 예정 실적으로 쌓는다.
 * 주문 상품이 모두 끝나면(구매확정·취소·반품) 구매확정된 금액 기준으로 확정, 하나도 없으면 취소.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PartnersService {

    /** 기본값 (실제 값은 관리자 설정 PARTNERS_RATE_PERCENT) */
    public static final double RATE = 0.03;

    /** 적립률 (관리자 설정 %) */
    public double rate() {
        return policyService.get(com.yangpang.domain.policy.Policy.PARTNERS_RATE_PERCENT) / 100.0;
    }
    public static final int COOKIE_HOURS = 24;
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Link(Long id, Long productId, String productName, String code, int clicks, long orders, long pending, long confirmed,
                       Instant createdAt) {
    }

    public record Summary(long clicks, long orders, long pending, long confirmedThisMonth, long confirmedTotal) {
    }

    private final JdbcTemplate jdbc;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final Clock clock;

    /** 내 링크 (이미 있으면 그대로) */
    public String linkCode(Long memberId, Long productId) {
        Boolean exists = jdbc.queryForObject("select exists(select 1 from product where id = ? and status <> 'DELETED')", Boolean.class, productId);
        if (!Boolean.TRUE.equals(exists)) throw new BusinessException("상품이 없어요");
        List<String> found = jdbc.queryForList("select code from affiliate_link where member_id = ? and product_id = ?", String.class, memberId, productId);
        if (!found.isEmpty()) return found.getFirst();
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomCode(8);
            int n = jdbc.update("""
                    insert into affiliate_link(member_id, product_id, code, created_at) values (?, ?, ?, ?)
                    on conflict do nothing""", memberId, productId, code, Timestamp.from(clock.instant()));
            if (n > 0) return code;
            found = jdbc.queryForList("select code from affiliate_link where member_id = ? and product_id = ?", String.class, memberId, productId);
            if (!found.isEmpty()) return found.getFirst();
        }
        throw new IllegalStateException("링크 코드를 만들지 못했어요");
    }

    static String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** 링크 방문: 클릭 수 +1, 이동할 상품 id (없는 코드면 null) */
    public Long visit(String code) {
        if (code == null || !code.matches("[A-Za-z0-9]{4,12}")) return null;
        List<Long> pid = jdbc.queryForList("update affiliate_link set clicks = clicks + 1 where code = ? returning product_id", Long.class, code);
        return pid.isEmpty() ? null : pid.getFirst();
    }

    /** 주문 생성 시: 파트너스 쿠키가 있으면 주문에 연결 (자기 링크로 산 것은 제외) */
    public void tagOrder(Long orderId, Long buyerId, String code) {
        if (code == null || code.isBlank()) return;
        jdbc.update("""
                update orders set affiliate_link_id = l.id from affiliate_link l
                where orders.id = ? and l.code = ? and l.member_id <> ?""", orderId, code, buyerId);
    }

    /** 결제 완료 시: 연결된 파트너스 링크가 있으면 예정 실적 */
    public void onPaid(Long orderId) {
        jdbc.update("""
                insert into affiliate_earning(link_id, order_id, base_amount, amount, status, created_at)
                select o.affiliate_link_id, o.id, s.base, floor(s.base * ?), 'PENDING', ?
                from orders o cross join lateral (select coalesce(sum(line_amount - discount_amount), 0) as base from order_item where order_id = o.id) s
                where o.id = ? and o.affiliate_link_id is not null
                on conflict (order_id) do nothing""", rate(), Timestamp.from(clock.instant()), orderId);
    }

    /** 시뮬레이터: 주문 상품이 모두 끝난 예정 실적을 확정·취소 */
    public int settle() {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.update("""
                update affiliate_earning e set
                    base_amount = x.confirmed,
                    amount = floor(x.confirmed * ?),
                    status = case when x.confirmed > 0 then 'CONFIRMED' else 'CANCELED' end,
                    decided_at = ?
                from (select oi.order_id,
                             sum(case when oi.status = 'CONFIRMED' then oi.line_amount - oi.discount_amount else 0 end) as confirmed,
                             bool_and(oi.status in ('CONFIRMED', 'RETURNED', 'CANCELED', 'PAYMENT_FAILED')) as ended
                      from order_item oi group by oi.order_id) x
                where e.status = 'PENDING' and x.order_id = e.order_id and x.ended""", rate(), now);
    }

    @Transactional(readOnly = true)
    public List<Link> links(Long memberId) {
        return jdbc.query("""
                select l.id, l.product_id, p.name, l.code, l.clicks,
                       count(e.id), coalesce(sum(e.amount) filter (where e.status = 'PENDING'), 0),
                       coalesce(sum(e.amount) filter (where e.status = 'CONFIRMED'), 0), l.created_at
                from affiliate_link l join product p on p.id = l.product_id left join affiliate_earning e on e.link_id = l.id
                where l.member_id = ? group by l.id, p.name order by l.created_at desc""",
                (rs, i) -> new Link(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6),
                        rs.getLong(7), rs.getLong(8), rs.getTimestamp(9).toInstant()), memberId);
    }

    @Transactional(readOnly = true)
    public Summary summary(Long memberId) {
        Instant monthStart = clock.instant().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        return jdbc.queryForObject("""
                select coalesce((select sum(clicks) from affiliate_link where member_id = ?), 0),
                       count(e.id) filter (where e.status <> 'CANCELED'),
                       coalesce(sum(e.amount) filter (where e.status = 'PENDING'), 0),
                       coalesce(sum(e.amount) filter (where e.status = 'CONFIRMED' and e.decided_at >= ?), 0),
                       coalesce(sum(e.amount) filter (where e.status = 'CONFIRMED'), 0)
                from affiliate_earning e join affiliate_link l on l.id = e.link_id where l.member_id = ?""",
                (rs, i) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                memberId, Timestamp.from(monthStart), memberId);
    }
}
