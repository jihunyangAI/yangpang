package com.yangpang.domain.order;

import com.yangpang.domain.common.DeliveryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** 주문 상품. 상품명·옵션·가격은 주문 시점 값을 복사(스냅샷)해 둔다 */
@Getter
@Entity
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;
    @Column(name = "product_id", nullable = false)
    private Long productId;
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "product_name", nullable = false)
    private String productName;
    @Column(name = "option_text", length = 120)
    private String optionText;
    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 20)
    private DeliveryType deliveryType;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;
    @Column(name = "line_amount", nullable = false)
    private long lineAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;
    @Column(name = "promised_date")
    private LocalDate promisedDate;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** 마지막으로 상태가 바뀐 시각 (시뮬레이터가 다음 단계로 넘길 때 기준) */
    @Column(length = 30)
    private String carrier;

    @Column(name = "tracking_no", length = 40)
    private String trackingNo;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "delivery_photo_url", length = 500)
    private String deliveryPhotoUrl;

    /** 사용한 상품 쿠폰 (member_coupon.id) */
    @Column(name = "product_coupon_id")
    private Long productCouponId;

    /** 양팡플러스 회원가로 아낀 금액 ((판매가 - 회원가) x 수량) */
    @Column(name = "plus_saving", nullable = false)
    private long plusSaving;

    /** 오늘의 특가로 산 경우 그 특가 (취소 시 수량 되돌림) */
    @Column(name = "deal_id")
    private Long dealId;

    /** 이 상품 몫으로 안분된 양팡캐시 */
    @Column(name = "cash_amount", nullable = false)
    private long cashAmount;

    static OrderItem create(Order order, Long vendorItemId, Long productId, Long sellerId, String productName,
                            String optionText, String imageUrl, DeliveryType deliveryType, long unitPrice,
                            int quantity, LocalDate promisedDate, Long productCouponId, long discountAmount, long cashAmount) {
        OrderItem i = new OrderItem();
        i.order = order;
        i.vendorItemId = vendorItemId;
        i.productId = productId;
        i.sellerId = sellerId;
        i.productName = productName;
        i.optionText = optionText;
        i.imageUrl = imageUrl;
        i.deliveryType = deliveryType;
        i.unitPrice = unitPrice;
        i.quantity = quantity;
        i.productCouponId = productCouponId;
        i.discountAmount = discountAmount;   // 상품 쿠폰 + 장바구니 쿠폰 안분
        i.cashAmount = cashAmount;           // 양팡캐시 안분
        i.lineAmount = unitPrice * quantity;
        i.status = order.getStatus();
        i.statusChangedAt = order.getOrderedAt();
        i.promisedDate = promisedDate;
        return i;
    }

    void setDealId(Long dealId) {
        this.dealId = dealId;
    }

    void setPlusSaving(long plusSaving) {
        this.plusSaving = plusSaving;
    }

    void changeStatus(OrderStatus next, Instant now) {
        this.status = next;
        this.statusChangedAt = now;
        if (next == OrderStatus.DELIVERED) {
            this.deliveredAt = now;
            this.deliveryPhotoUrl = "/images/delivery/" + id + ".svg"; // 가짜 배송 완료 사진
        }
        if (next == OrderStatus.CONFIRMED) this.confirmedAt = now;
    }

    /** 교환: 새 옵션으로 바꿔 다시 보낸다 (가격은 같은 상품이라 그대로) */
    public void switchOffer(Long newVendorItemId, String newOptionText) {
        this.vendorItemId = newVendorItemId;
        this.optionText = newOptionText;
        this.deliveredAt = null;
        this.deliveryPhotoUrl = null;
    }

    /** 판매자 송장 등록 */
    public void registerTracking(String carrier, String trackingNo) {
        this.carrier = carrier;
        this.trackingNo = trackingNo;
    }

    /** 지금부터 이 단계를 다시 센다 (선물 배송지를 받은 뒤 발주 확인까지의 시간) */
    public void restartStage(Instant now) {
        this.statusChangedAt = now;
    }

    /** 다시 배송할 때의 도착 예정일 */
    public void repromise(LocalDate date) {
        this.promisedDate = date;
    }

    /** 이 상품에 낸 금액 (상품 금액 - 할인 안분). 이 중 cashAmount는 캐시로, 나머지는 결제수단으로 냈다 */
    public long paidAmount() {
        return lineAmount - discountAmount;
    }

    /** 결제 후 아직 취소·반품되지 않은 상품인지 */
    public boolean isActive() {
        return status.step() >= 0;
    }
}
