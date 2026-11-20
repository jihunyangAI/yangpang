package com.yangpang.domain.membership;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 멤버십 모의 청구 기록 (실제 결제 없음) */
@Getter
@Entity
@Table(name = "membership_billing")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipBilling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 20)
    private String kind;   // FREE_TRIAL / MONTHLY

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "billed_at", nullable = false)
    private Instant billedAt;

    static MembershipBilling of(Membership m, long amount, String kind, Instant billedAt) {
        MembershipBilling b = new MembershipBilling();
        b.membershipId = m.getId();
        b.amount = amount;
        b.kind = kind;
        b.periodStart = m.getCurrentPeriodStart();
        b.periodEnd = m.getCurrentPeriodEnd();
        b.billedAt = billedAt;
        return b;
    }

    public String kindLabel() {
        return "FREE_TRIAL".equals(kind) ? "첫 달 무료" : "월 회비 (모의 청구)";
    }
}
