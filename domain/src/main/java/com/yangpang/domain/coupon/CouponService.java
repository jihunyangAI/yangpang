package com.yangpang.domain.coupon;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 쿠폰 받기·자동 지급·사용·복원 (기획서 5.12, 15장) */
@Service
@RequiredArgsConstructor
@Transactional
public class CouponService {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final Clock clock;

    /** 쿠폰 받기 (회원당 1장) */
    public MemberCoupon download(Long memberId, Long couponId) {
        Coupon coupon = couponRepository.findForIssue(couponId).orElseThrow(() -> new BusinessException("쿠폰이 없어요"));
        if (!coupon.isDownloadable()) throw new BusinessException("받을 수 없는 쿠폰이에요");
        return issue(memberId, coupon);
    }

    /** 가입 등 자동 지급 쿠폰 */
    public void issueAuto(Long memberId, String trigger) {
        for (Coupon c : couponRepository.findByActiveTrueAndAutoIssue(trigger)) {
            if (!memberCouponRepository.existsByMemberIdAndCouponId(memberId, c.getId())) {
                issue(memberId, couponRepository.findForIssue(c.getId()).orElseThrow());
            }
        }
    }

    private MemberCoupon issue(Long memberId, Coupon coupon) {
        Instant now = clock.instant();
        if (!coupon.isIssuable(now)) throw new BusinessException("쿠폰이 모두 소진되었거나 기간이 끝났어요");
        if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, coupon.getId())) {
            throw new BusinessException("이미 받은 쿠폰이에요");
        }
        coupon.issued();
        return memberCouponRepository.save(MemberCoupon.issue(memberId, coupon, now));
    }

    /** 지금 쓸 수 있는 내 쿠폰 (기간 지난 것은 만료로 바꿔 둔다) */
    public List<MemberCoupon> usable(Long memberId) {
        Instant now = clock.instant();
        List<MemberCoupon> available = memberCouponRepository.findAvailable(memberId);
        available.stream().filter(m -> !m.isUsable(now)).forEach(m -> m.restore(now)); // 기간 지남 → EXPIRED
        return available.stream().filter(m -> m.isUsable(now)).toList();
    }

    /** 주문에 사용 표시. 쓸 수 없는 쿠폰이 섞여 있으면 실패 */
    public void use(Long memberId, Collection<Long> memberCouponIds, Long orderId) {
        if (memberCouponIds.isEmpty()) return;
        Instant now = clock.instant();
        List<MemberCoupon> coupons = memberCouponRepository.findMine(memberCouponIds, memberId);
        if (coupons.size() != memberCouponIds.size() || coupons.stream().anyMatch(c -> !c.isUsable(now))) {
            throw new BusinessException("사용할 수 없는 쿠폰이 있어요. 주문서를 다시 확인해 주세요");
        }
        coupons.forEach(c -> c.use(orderId, now));
    }

    /** 주문 전체 취소·결제 실패 시 그 주문에 쓴 쿠폰을 되돌린다 */
    public void restoreForOrder(Long orderId) {
        Instant now = clock.instant();
        memberCouponRepository.findByUsedOrderId(orderId).forEach(c -> c.restore(now));
    }
}
