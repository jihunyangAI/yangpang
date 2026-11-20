package com.yangpang.domain.claim;

import com.yangpang.domain.order.OrderItem;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "claim_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id")
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    /** 교환: 새로 받을 옵션의 판매 오퍼 */
    @Column(name = "exchange_vendor_item_id")
    private Long exchangeVendorItemId;

    @Column(name = "refund_amount", nullable = false)
    private long refundAmount;

    static ClaimItem create(Claim claim, OrderItem orderItem, Long exchangeVendorItemId, long refundAmount) {
        ClaimItem i = new ClaimItem();
        i.claim = claim;
        i.orderItem = orderItem;
        i.exchangeVendorItemId = exchangeVendorItemId;
        i.refundAmount = refundAmount;
        return i;
    }
}
