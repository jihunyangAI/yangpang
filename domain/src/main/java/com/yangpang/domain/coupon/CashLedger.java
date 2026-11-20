package com.yangpang.domain.coupon;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 양팡캐시 원장 한 줄. 잔액 = 회원의 amount 합계 */
@Getter
@Entity
@Table(name = "cash_ledger")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashLedger {

    public enum Type {
        EARN_PURCHASE("구매 적립"), EARN_REVIEW("리뷰 적립"), EARN_DELAY("배송 지연 보상"), EARN_EVENT("이벤트 적립"),
        USE("주문 사용"), USE_CANCEL("사용 취소"), REFUND("환불"), EXPIRE("유효기간 만료");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private Type type;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static CashLedger of(Long memberId, long amount, Type type, Long orderId, String description, Instant expiresAt, Instant now) {
        CashLedger l = new CashLedger();
        l.memberId = memberId;
        l.amount = amount;
        l.type = type;
        l.orderId = orderId;
        l.description = description;
        l.expiresAt = expiresAt;
        l.createdAt = now;
        return l;
    }
}
