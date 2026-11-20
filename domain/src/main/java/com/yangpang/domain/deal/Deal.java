package com.yangpang.domain.deal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 오늘의 특가 한 건 (판매 오퍼 하나에 기간·수량 한정 가격) */
@Getter
@Entity
@Table(name = "deal")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "deal_price", nullable = false)
    private long dealPrice;

    @Column(nullable = false)
    private int quantity;

    /** 판매된 수량. 엔티티로 바꾸지 않고 DealRepository의 조건부 UPDATE로만 바꾼다 */
    @Column(nullable = false, insertable = false, updatable = false)
    private int sold;

    @Column(name = "max_per_order", nullable = false)
    private int maxPerOrder;

    @Column(name = "max_per_member", nullable = false)
    private int maxPerMember;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    static Deal create(Long vendorItemId, long dealPrice, int quantity, int maxPerOrder, int maxPerMember,
                       Instant startsAt, Instant endsAt, String source) {
        Deal d = new Deal();
        d.vendorItemId = vendorItemId;
        d.dealPrice = dealPrice;
        d.quantity = quantity;
        d.maxPerOrder = maxPerOrder;
        d.maxPerMember = maxPerMember;
        d.startsAt = startsAt;
        d.endsAt = endsAt;
        d.source = source;
        return d;
    }

    public int remaining() {
        return Math.max(0, quantity - sold);
    }

    public boolean isSoldOut() {
        return remaining() == 0;
    }

    public boolean isActive(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /** 판매 진행률 (0~100) */
    public int soldPercent() {
        return (int) Math.round(100.0 * sold / quantity);
    }
}
