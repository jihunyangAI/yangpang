package com.yangpang.domain.coupon;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 회원이 받은 쿠폰 한 장 */
@Getter
@Entity
@Table(name = "member_coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon {

    public enum Status {AVAILABLE, USED, EXPIRED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    static MemberCoupon issue(Long memberId, Coupon coupon, Instant now) {
        MemberCoupon m = new MemberCoupon();
        m.memberId = memberId;
        m.coupon = coupon;
        m.status = Status.AVAILABLE;
        m.issuedAt = now;
        m.expiresAt = now.plusSeconds(coupon.getValidDays() * 86_400L);
        return m;
    }

    public boolean isUsable(Instant now) {
        return status == Status.AVAILABLE && now.isBefore(expiresAt);
    }

    void use(Long orderId, Instant now) {
        this.status = Status.USED;
        this.usedOrderId = orderId;
        this.usedAt = now;
    }

    /** 주문 취소 등으로 되돌림. 기간이 지났으면 만료 처리 */
    void restore(Instant now) {
        this.status = now.isBefore(expiresAt) ? Status.AVAILABLE : Status.EXPIRED;
        this.usedOrderId = null;
        this.usedAt = null;
    }

    public CouponSpec toSpec() {
        return coupon.toSpec(id);
    }
}
