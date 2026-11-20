package com.yangpang.domain.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 모의 결제 기록 (기획서 6장). 실제 PG 연동 없음 */
@Getter
@Entity
@Table(name = "mock_payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockPayment {

    public enum Method {
        YANGPAY("양페이"), CARD("신용·체크카드"), PHONE("휴대폰 결제"), BANK("무통장입금(가상계좌)");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {APPROVED, FAILED, PENDING}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Method method;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "deposit_deadline")
    private Instant depositDeadline;

    @Column(name = "virtual_account", length = 40)
    private String virtualAccount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static MockPayment of(Long orderId, Method method, long amount, Status status, Instant now) {
        MockPayment p = new MockPayment();
        p.orderId = orderId;
        p.method = method;
        p.amount = amount;
        p.status = status;
        p.createdAt = now;
        if (status == Status.APPROVED) p.approvedAt = now;
        if (status == Status.PENDING) {
            p.depositDeadline = now.plusSeconds(24 * 3600);
            p.virtualAccount = "가상은행 000-" + String.format("%06d", orderId % 1_000_000) + "-00 (모의)";
        }
        return p;
    }
}
