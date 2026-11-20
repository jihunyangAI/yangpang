package com.yangpang.domain.subscription;

import com.yangpang.domain.order.MockPayment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 정기배송 (M4 ④). 상품 옵션 하나를 정해진 주기(1·2·4주)로 받는다.
 * 다음 배송 예정일 이틀 전에 시뮬레이터가 주문을 만들고 등록한 모의 결제수단으로 결제한다.
 */
@Getter
@Entity
@Table(name = "subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    /** 기본값 (실제 값은 관리자 설정 SUBSCRIPTION_DISCOUNT_PERCENT) */
    public static final int DISCOUNT_PERCENT = 5;
    /** 배송 예정일 며칠 전에 주문·결제하는지 */
    public static final int ORDER_LEAD_DAYS = 2;
    public static final int MAX_QUANTITY = 10;

    public enum Status {ACTIVE, CANCELED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "cycle_weeks", nullable = false)
    private int cycleWeeks;

    @Column(name = "address_id")
    private Long addressId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MockPayment.Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "next_delivery_date", nullable = false)
    private LocalDate nextDeliveryDate;

    @Column(name = "last_delivery_date")
    private LocalDate lastDeliveryDate;

    @Column(nullable = false)
    private int rounds;

    @Column(nullable = false)
    private int skipped;

    @Column(name = "last_note", length = 200)
    private String lastNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    static Subscription create(Long memberId, Long vendorItemId, Long productId, int quantity, int cycleWeeks,
                               Long addressId, MockPayment.Method method, LocalDate firstDelivery, Instant now) {
        Subscription s = new Subscription();
        s.memberId = memberId;
        s.vendorItemId = vendorItemId;
        s.productId = productId;
        s.quantity = quantity;
        s.cycleWeeks = cycleWeeks;
        s.addressId = addressId;
        s.method = method;
        s.status = Status.ACTIVE;
        s.nextDeliveryDate = firstDelivery;
        s.createdAt = now;
        return s;
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    /** 이번 회차 주문·결제할 날 */
    public LocalDate nextOrderDate() {
        return nextDeliveryDate.minusDays(ORDER_LEAD_DAYS);
    }

    public boolean due(LocalDate today) {
        return active() && !today.isBefore(nextOrderDate());
    }

    /** 회차 주문 완료: 실제 도착 예정일 기준으로 다음 회차를 잡는다 */
    void ordered(LocalDate promised) {
        rounds++;
        lastDeliveryDate = promised;
        nextDeliveryDate = promised.plusWeeks(cycleWeeks);
        lastNote = null;
    }

    /** 이번 회차 건너뛰기 (직접 또는 재고 부족 등) */
    void skip(String note) {
        skipped++;
        nextDeliveryDate = nextDeliveryDate.plusWeeks(cycleWeeks);
        lastNote = note;
    }

    /** 주기 변경: 마지막 배송일부터 새 주기로 다시 계산하되, 주문일이 오늘보다 앞서지 않게 */
    void changeCycle(int weeks, LocalDate today) {
        this.cycleWeeks = weeks;
        LocalDate base = lastDeliveryDate != null ? lastDeliveryDate.plusWeeks(weeks) : today.plusWeeks(weeks);
        LocalDate earliest = today.plusDays(ORDER_LEAD_DAYS + 1);
        this.nextDeliveryDate = base.isBefore(earliest) ? earliest : base;
    }

    void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    void changeAddress(Long addressId) {
        this.addressId = addressId;
    }

    void changeMethod(MockPayment.Method method) {
        this.method = method;
    }

    void cancel(Instant now) {
        this.status = Status.CANCELED;
        this.canceledAt = now;
    }

    public static void validateCycle(int weeks) {
        if (weeks != 1 && weeks != 2 && weeks != 4) throw new com.yangpang.domain.common.BusinessException("배송 주기는 1·2·4주 중에서 골라 주세요");
    }

    public static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) throw new com.yangpang.domain.common.BusinessException("정기배송 수량은 1~" + MAX_QUANTITY + "개예요");
    }

    public static void validateMethod(MockPayment.Method method) {
        if (method == null || method == MockPayment.Method.BANK) {
            throw new com.yangpang.domain.common.BusinessException("정기배송은 자동 결제라 무통장입금을 쓸 수 없어요");
        }
    }
}
