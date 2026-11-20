package com.yangpang.domain.membership;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/** 양팡플러스 멤버십 (회원당 1개, 해지 후 재가입 시 같은 행을 다시 씀) */
@Getter
@Entity
@Table(name = "membership")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

    public enum Status {
        ACTIVE("이용 중"), CANCELED("해지 예정"), ENDED("해지됨");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final Duration PERIOD = Duration.ofDays(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "monthly_fee", nullable = false)
    private long monthlyFee;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "trial_used", nullable = false)
    private boolean trialUsed;

    static Membership start(Long memberId, long fee, Instant now) {
        Membership m = new Membership();
        m.memberId = memberId;
        m.monthlyFee = fee;
        m.status = Status.ACTIVE;
        m.startedAt = now;
        m.currentPeriodStart = now;
        m.currentPeriodEnd = now.plus(PERIOD);
        m.trialUsed = true;
        return m;
    }

    /** 해지 후 다시 가입 (무료 체험 없음) */
    void restart(long fee, Instant now) {
        this.status = Status.ACTIVE;
        this.monthlyFee = fee;
        this.startedAt = now;
        this.currentPeriodStart = now;
        this.currentPeriodEnd = now.plus(PERIOD);
        this.canceledAt = null;
        this.endedAt = null;
    }

    void cancel(Instant now) {
        this.status = Status.CANCELED;
        this.canceledAt = now;
    }

    void undoCancel() {
        this.status = Status.ACTIVE;
        this.canceledAt = null;
    }

    /** 다음 기간으로 갱신 (청구는 서비스에서 기록) */
    void renew() {
        this.currentPeriodStart = currentPeriodEnd;
        this.currentPeriodEnd = currentPeriodEnd.plus(PERIOD);
    }

    void end(Instant at) {
        this.status = Status.ENDED;
        this.endedAt = at;
    }

    /** 혜택을 받을 수 있는지 (해지 예정이어도 기간이 끝날 때까지는 혜택 유지) */
    public boolean isBenefitActive() {
        return status == Status.ACTIVE || status == Status.CANCELED;
    }
}
