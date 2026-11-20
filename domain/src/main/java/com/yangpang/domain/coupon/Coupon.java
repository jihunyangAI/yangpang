package com.yangpang.domain.coupon;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 쿠폰 정의 (관리자가 만든다) */
@Getter
@Entity
@Table(name = "coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_type", nullable = false, length = 20)
    private CouponSpec.Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private CouponSpec.DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount")
    private Long maxDiscount;

    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    @Column(name = "target_category_id")
    private Long targetCategoryId;

    @Column(name = "target_product_id")
    private Long targetProductId;

    @Column(name = "valid_days", nullable = false)
    private int validDays;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(nullable = false)
    private boolean downloadable;

    @Column(name = "auto_issue", length = 20)
    private String autoIssue;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static Coupon create(String name, CouponSpec.Kind kind, CouponSpec.DiscountType discountType, long value,
                                Long maxDiscount, long minOrderAmount, Long targetCategoryId, int validDays,
                                Integer totalQuantity, boolean downloadable, String autoIssue, Instant now) {
        Coupon c = new Coupon();
        c.name = name;
        c.kind = kind;
        c.discountType = discountType;
        c.discountValue = value;
        c.maxDiscount = maxDiscount;
        c.minOrderAmount = minOrderAmount;
        c.targetCategoryId = targetCategoryId;
        c.validDays = validDays;
        c.totalQuantity = totalQuantity;
        c.downloadable = downloadable;
        c.autoIssue = autoIssue;
        c.active = true;
        c.createdAt = now;
        return c;
    }

    void issued() {
        issuedCount++;
    }

    public boolean isSoldOut() {
        return totalQuantity != null && issuedCount >= totalQuantity;
    }

    public boolean isIssuable(Instant now) {
        return active && !isSoldOut() && (endsAt == null || now.isBefore(endsAt));
    }

    public void deactivate() {
        this.active = false;
    }

    public CouponSpec toSpec(long memberCouponId) {
        return new CouponSpec(memberCouponId, name, kind, discountType, discountValue, maxDiscount, minOrderAmount,
                targetCategoryId, targetProductId);
    }

    public String benefitText() {
        return toSpec(0).benefitText();
    }
}
