package com.yangpang.domain.claim;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 모의 환불 기록. 실제 돈은 움직이지 않는다 */
@Getter
@Entity
@Table(name = "refund")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    public enum Method {
        ORIGINAL("원결제수단 (모의)"), CASH("양팡캐시");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Method method;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static Refund completed(Long claimId, Long orderId, long amount, Method method, Instant now) {
        Refund r = new Refund();
        r.claimId = claimId;
        r.orderId = orderId;
        r.amount = amount;
        r.method = method;
        r.status = "COMPLETED";
        r.createdAt = now;
        return r;
    }
}
