package com.yangpang.domain.coupon;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 양팡캐시 (기획서 15장).
 * 적립: 양페이 결제 상품 구매확정 시 결제액의 1%, 리뷰 50원(사진 리뷰 100원), 배송 지연 보상 1,000원.
 * 적립분은 1년 유효기간을 기록한다 (소멸 처리는 이후 단계).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CashService {

    public static final int PURCHASE_RATE_PERCENT = 1;
    public static final long REVIEW_TEXT = 50;
    public static final long REVIEW_PHOTO = 100;
    public static final long DELAY_COMPENSATION = 1_000;
    static final Duration VALIDITY = Duration.ofDays(365);

    private final CashLedgerRepository ledgerRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public long balance(Long memberId) {
        return ledgerRepository.balance(memberId);
    }

    public void earn(Long memberId, long amount, CashLedger.Type type, Long orderId, String description) {
        if (amount <= 0) return;
        Instant now = clock.instant();
        ledgerRepository.save(CashLedger.of(memberId, amount, type, orderId, description, now.plus(VALIDITY), now));
    }

    /** 같은 일로 두 번 적립하지 않도록 (예: 배송 지연 보상은 주문 상품당 한 번) */
    public void earnOnce(Long memberId, long amount, CashLedger.Type type, Long orderId, String description) {
        if (ledgerRepository.existsByMemberIdAndTypeAndOrderIdAndDescription(memberId, type, orderId, description)) return;
        earn(memberId, amount, type, orderId, description);
    }

    public void use(Long memberId, long amount, Long orderId) {
        if (amount <= 0) return;
        if (balance(memberId) < amount) throw new BusinessException("양팡캐시 잔액이 부족해요");
        ledgerRepository.save(CashLedger.of(memberId, -amount, CashLedger.Type.USE, orderId, "주문 결제에 사용", null, clock.instant()));
    }

    /** 사용 취소(결제 실패·입금 전 취소) 또는 환불로 돌려준다 */
    public void giveBack(Long memberId, long amount, CashLedger.Type type, Long orderId, String description) {
        if (amount <= 0) return;
        Instant now = clock.instant();
        ledgerRepository.save(CashLedger.of(memberId, amount, type, orderId, description, now.plus(VALIDITY), now));
    }

    // ---------------------------------------------------------------------
    // 소멸 (적립 후 1년). 시뮬레이터가 시뮬레이션 시각으로 1시간마다 확인
    // ---------------------------------------------------------------------

    private java.util.List<CashExpiry.Credit> credits(Long memberId) {
        return jdbc.query("select id, amount, expires_at from cash_ledger where member_id = ? and amount > 0 and expires_at is not null",
                (rs, n) -> new CashExpiry.Credit(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant()), memberId);
    }

    private long spent(Long memberId) {
        Long v = jdbc.queryForObject("select coalesce(-sum(amount), 0) from cash_ledger where member_id = ? and amount < 0", Long.class, memberId);
        return v == null ? 0 : v;
    }

    /** 30일 안에 소멸될 금액 (마이양팡 안내) */
    @Transactional(readOnly = true)
    public long expiringSoon(Long memberId) {
        return CashExpiry.expiringWithin(credits(memberId), spent(memberId), clock.instant(), java.time.Duration.ofDays(30));
    }

    /** 유효기간이 지난 남은 캐시를 소멸 처리. 소멸시킨 회원 수를 돌려준다 */
    public int expireDue() {
        Instant now = clock.instant();
        java.util.List<Long> members = jdbc.queryForList("""
                select distinct member_id from cash_ledger where amount > 0 and expires_at <= ?""", Long.class, java.sql.Timestamp.from(now));
        int n = 0;
        for (Long m : members) {
            long expired = CashExpiry.expiredAmount(credits(m), spent(m), now);
            if (expired <= 0) continue;
            ledgerRepository.save(CashLedger.of(m, -expired, CashLedger.Type.EXPIRE, null, "적립 후 1년이 지나 소멸", null, now));
            notificationService.notify(m, com.yangpang.domain.notification.Notification.Type.BENEFIT, "양팡캐시가 소멸됐어요",
                    String.format("%,d", expired) + "원 · 적립 후 1년이 지난 캐시예요", "/my/cash", null);
            n++;
        }
        return n;
    }
}
