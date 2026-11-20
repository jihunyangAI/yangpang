package com.yangpang.domain.order;

import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.common.Region;
import com.yangpang.domain.member.Address;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 주문. 배송지는 주문 시점 값을 복사(스냅샷)해 둔다 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 30)
    private String orderNo;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    // ---- 배송지 스냅샷 ----
    @Column(nullable = false, length = 50)
    private String recipient;
    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;
    @Column(nullable = false, length = 10)
    private String zipcode;
    @Column(nullable = false)
    private String address1;
    private String address2;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Region region;
    @Enumerated(EnumType.STRING)
    @Column(name = "entrance_type", nullable = false, length = 20)
    private EntranceType entranceType;
    @Column(name = "entrance_note", length = 100)
    private String entranceNote;
    @Column(name = "request_note", length = 100)
    private String requestNote;

    // ---- 금액 ----
    @Column(name = "total_product_amount", nullable = false)
    private long totalProductAmount;
    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;
    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;
    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;
    @Column(name = "paid_at")
    private Instant paidAt;

    /** 쿠폰 할인 합계 (상품 쿠폰 + 장바구니 쿠폰) */
    @Column(name = "coupon_discount", nullable = false)
    private long couponDiscount;

    /** 양팡캐시 사용액 */
    @Column(name = "cash_used", nullable = false)
    private long cashUsed;

    /** 사용한 장바구니 쿠폰 (member_coupon.id) */
    @Column(name = "cart_coupon_id")
    private Long cartCouponId;

    /** 양팡플러스 무료배송으로 아낀 배송비 */
    @Column(name = "shipping_saved", nullable = false)
    private long shippingSaved;

    /** 지금까지 환불한 금액 (모의) */
    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    /** 양팡설치 상품이 있는 주문의 방문 설치일 */
    @Column(name = "install_date")
    private java.time.LocalDate installDate;

    void setInstallDate(java.time.LocalDate date) {
        this.installDate = date;
    }

    /** 직구 상품이 있는 주문의 개인통관고유부호 (가짜, P + 숫자 12자리) */
    @Column(name = "customs_code", length = 13)
    private String customsCode;

    /** 정기배송으로 만든 주문이면 그 정기배송 id */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /** 선물: 받는 분이 아직 배송지를 입력하지 않음 (이동안 출고하지 않는다) */
    @Column(name = "gift_waiting", nullable = false)
    private boolean giftWaiting;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id asc")
    private List<OrderItem> items = new ArrayList<>();

    static Order create(String orderNo, Long memberId, Address address, String requestNote,
                        long shippingFee, Instant now) {
        Order o = new Order();
        o.orderNo = orderNo;
        o.memberId = memberId;
        o.status = OrderStatus.PENDING_PAYMENT;
        o.recipient = address.getRecipient();
        o.recipientPhone = address.getPhone();
        o.zipcode = address.getZipcode();
        o.address1 = address.getAddress1();
        o.address2 = address.getAddress2();
        o.region = address.getRegion();
        o.entranceType = address.getEntranceType();
        o.entranceNote = address.getEntranceNote();
        o.requestNote = requestNote;
        o.shippingFee = shippingFee;
        o.orderedAt = now;
        return o;
    }

    /**
     * 선물 주문: 배송지는 받는 분이 링크에서 입력하기 전까지 비워 둔다(자리 표시 값).
     * 권역은 우선 수도권으로 두고, 배송지를 받으면 다시 계산한다.
     */
    static Order createGift(String orderNo, Long memberId, String recipientName, String recipientPhone,
                            long shippingFee, Instant now) {
        Order o = new Order();
        o.orderNo = orderNo;
        o.memberId = memberId;
        o.status = OrderStatus.PENDING_PAYMENT;
        o.recipient = recipientName;
        o.recipientPhone = recipientPhone;
        o.zipcode = "-";
        o.address1 = "선물 받는 분이 배송지를 입력하기 전이에요";
        o.region = Region.METRO;
        o.entranceType = EntranceType.FREE;
        o.shippingFee = shippingFee;
        o.orderedAt = now;
        o.giftWaiting = true;
        return o;
    }

    /** 받는 분이 선물 링크에서 배송지를 입력함 */
    public void applyGiftAddress(String zipcode, String address1, String address2, Region region,
                                 EntranceType entranceType, String entranceNote, String requestNote) {
        this.zipcode = zipcode;
        this.address1 = address1;
        this.address2 = address2;
        this.region = region;
        this.entranceType = entranceType;
        this.entranceNote = entranceNote;
        this.requestNote = requestNote;
        this.giftWaiting = false;
    }

    void setCustomsCode(String code) {
        this.customsCode = code;
    }

    /** P12******5678 처럼 가운데를 가린 통관부호 */
    public String maskedCustomsCode() {
        return maskCustomsCode(customsCode);
    }

    public static String maskCustomsCode(String code) {
        return code == null || code.length() < 7 ? code : code.substring(0, 3) + "*".repeat(code.length() - 7) + code.substring(code.length() - 4);
    }

    void markSubscription(Long subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    /** 실제 결제금액(모의 PG) = 상품금액 - 쿠폰 할인 - 캐시 + 배송비 */
    void addItem(OrderItem item) {
        items.add(item);
        totalProductAmount += item.getUnitPrice() * item.getQuantity();
        discountAmount += item.getDiscountAmount();
        couponDiscount += item.getDiscountAmount();
        cashUsed += item.getCashAmount();
        paidAmount = totalProductAmount - discountAmount - cashUsed + shippingFee;
    }

    void setShippingSaved(long amount) {
        this.shippingSaved = amount;
    }

    void useCartCoupon(Long memberCouponId) {
        this.cartCouponId = memberCouponId;
    }

    /** 주문 전체(모든 상품)를 같은 상태로 (결제 승인·실패 등) */
    void changeStatus(OrderStatus next, Instant now) {
        this.status = next;
        items.forEach(i -> i.changeStatus(next, now));
        if (next == OrderStatus.PAID) this.paidAt = now;
    }

    /** 상품 하나의 상태를 바꾸고 주문 상태를 다시 계산한다 (배송 진행, 부분 취소·반품) */
    public void changeItemStatus(OrderItem item, OrderStatus next, Instant now) {
        item.changeStatus(next, now);
        refreshStatus();
    }

    /**
     * 주문 상태 = 아직 진행 중인 상품 중 가장 늦은 단계.
     * 예: 양팡배송 상품은 배송완료, 판매자배송 상품은 배송중 → 주문은 '배송중'.
     * 모두 취소·실패면 취소(또는 결제실패).
     */
    void refreshStatus() {
        this.status = items.stream()
                .filter(OrderItem::isActive)
                .map(OrderItem::getStatus)
                .min(java.util.Comparator.comparingInt(OrderStatus::step))
                .orElseGet(() -> items.stream().anyMatch(i -> i.getStatus() == OrderStatus.RETURNED) ? OrderStatus.RETURNED
                        : items.stream().anyMatch(i -> i.getStatus() == OrderStatus.CANCELED) ? OrderStatus.CANCELED
                        : OrderStatus.PAYMENT_FAILED);
    }

    /** 이 상품들을 빼면 진행 중인 상품이 하나도 안 남는지 (마지막 상품까지 취소·반품 → 배송비도 환불) */
    public boolean allEndedExcept(java.util.Collection<Long> orderItemIds) {
        return items.stream().filter(i -> !orderItemIds.contains(i.getId())).noneMatch(OrderItem::isActive);
    }

    public void addRefund(long amount) {
        this.refundedAmount += amount;
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    /** 주문 목록 제목: "첫 상품명 외 n건" */
    public String title() {
        if (items.isEmpty()) return "";
        String first = items.getFirst().getProductName();
        return items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건";
    }
}
