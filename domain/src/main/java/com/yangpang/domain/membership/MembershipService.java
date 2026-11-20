package com.yangpang.domain.membership;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.member.MemberGrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 양팡플러스 (기획서 5.8).
 * - 첫 가입은 30일 무료 체험, 이후 30일마다 월 회비(참고값 7,890원)를 "모의 청구"로 기록
 * - 해지는 2단계(해지하기 → 해지 완료). 해지해도 이번 기간이 끝날 때까지 혜택 유지, 기간 끝에 종료
 * - 혜택 여부는 member.grade(PLUS)로 빠르게 판단한다 (가입·종료 때 함께 바꿈)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MembershipService {

    /** 기본값 (실제 값은 관리자 설정 PLUS_MONTHLY_FEE) */
    public static final long MONTHLY_FEE = 7_890;

    /** 이번 기간에 아낀 금액 */
    public record Savings(long priceSaving, long shippingSaved) {
        public long total() {
            return priceSaving + shippingSaved;
        }
    }

    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final MembershipRepository membershipRepository;
    private final MembershipBillingRepository billingRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Membership join(Long memberId) {
        Instant now = clock.instant();
        Optional<Membership> existing = membershipRepository.findByMemberId(memberId);
        Membership m;
        if (existing.isEmpty()) {
            m = membershipRepository.save(Membership.start(memberId, fee(), now));
            billingRepository.save(MembershipBilling.of(m, 0, "FREE_TRIAL", now));
        } else {
            m = existing.get();
            if (m.isBenefitActive()) throw new BusinessException("이미 양팡플러스 회원이에요");
            m.restart(fee(), now);
            billingRepository.save(MembershipBilling.of(m, fee(), "MONTHLY", now));
        }
        setGrade(memberId, MemberGrade.PLUS);
        return m;
    }

    /** 해지 신청 (기간 끝까지 혜택 유지) */
    public Membership cancel(Long memberId) {
        Membership m = mine(memberId);
        if (m.getStatus() != Membership.Status.ACTIVE) throw new BusinessException("이용 중인 멤버십이 아니에요");
        m.cancel(clock.instant());
        return m;
    }

    /** 해지 신청 취소 */
    public void undoCancel(Long memberId) {
        Membership m = mine(memberId);
        if (m.getStatus() != Membership.Status.CANCELED) throw new BusinessException("해지 예정 상태가 아니에요");
        m.undoCancel();
    }

    /** 시뮬레이터: 기간이 끝난 멤버십 갱신(모의 청구) 또는 종료. 처리한 수를 돌려준다 */
    public int processDue() {
        Instant now = clock.instant();
        int n = 0;
        for (Membership m : membershipRepository.findDue(now)) {
            // 오래 건너뛴 경우 밀린 기간만큼 반복
            while (m.getStatus() != Membership.Status.ENDED && !m.getCurrentPeriodEnd().isAfter(now)) {
                if (m.getStatus() == Membership.Status.CANCELED) {
                    m.end(m.getCurrentPeriodEnd());
                    setGrade(m.getMemberId(), MemberGrade.NORMAL);
                    notificationService.notify(m.getMemberId(), com.yangpang.domain.notification.Notification.Type.BENEFIT, "양팡플러스 이용이 끝났어요",
                            "언제든 다시 가입할 수 있어요", "/plus", null);
                } else {
                    Instant billedAt = m.getCurrentPeriodEnd();
                    m.renew();
                    billingRepository.save(MembershipBilling.of(m, m.getMonthlyFee(), "MONTHLY", billedAt));
                    notificationService.notify(m.getMemberId(), com.yangpang.domain.notification.Notification.Type.BENEFIT, "양팡플러스가 갱신되었어요",
                            String.format("%,d", m.getMonthlyFee()) + "원 모의 청구 · 다음 결제일까지 무료배송 혜택이 이어져요", "/my/plus", null);
                }
            }
            n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public Optional<Membership> find(Long memberId) {
        return membershipRepository.findByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public Savings savings(Membership m) {
        Timestamp from = Timestamp.from(m.getCurrentPeriodStart());
        Long price = jdbc.queryForObject("""
                select coalesce(sum(oi.plus_saving), 0) from order_item oi join orders o on o.id = oi.order_id
                where o.member_id = ? and o.ordered_at >= ? and oi.status not in ('CANCELED','PAYMENT_FAILED','RETURNED')""",
                Long.class, m.getMemberId(), from);
        Long shipping = jdbc.queryForObject("""
                select coalesce(sum(shipping_saved), 0) from orders
                where member_id = ? and ordered_at >= ? and status not in ('CANCELED','PAYMENT_FAILED','PENDING_PAYMENT')""",
                Long.class, m.getMemberId(), from);
        return new Savings(price == null ? 0 : price, shipping == null ? 0 : shipping);
    }

    private Membership mine(Long memberId) {
        return membershipRepository.findByMemberId(memberId).orElseThrow(() -> new BusinessException("멤버십 정보가 없어요"));
    }

    private void setGrade(Long memberId, MemberGrade grade) {
        // 비즈 회원은 등급을 건드리지 않는다
        jdbc.update("update member set grade = ?, updated_at = now() where id = ? and grade <> 'BIZ'", grade.name(), memberId);
    }

    /** 월 회비 (관리자 설정) */
    public long fee() {
        return policyService.get(com.yangpang.domain.policy.Policy.PLUS_MONTHLY_FEE);
    }
}
