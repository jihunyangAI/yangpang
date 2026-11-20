package com.yangpang.domain.sim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 빨리 감을 수 있는 시계 (기획서 7.4).
 * 시뮬레이션 시각 = baseSim + (실제 지금 - baseReal) x speed
 * 예: speed 60 → 실제 1분이 시뮬레이션 1시간.
 * 앱의 "지금"(도착일, 주문 시각, 배송 진행 등)은 모두 이 시계를 따른다.
 */
public class SimulatedClock extends Clock {

    /** 한 번에 바꿔 끼우는 시계 상태 (불변) */
    public record State(Instant baseReal, Instant baseSim, double speed) {
        public Instant simAt(Instant real) {
            long realMillis = Duration.between(baseReal, real).toMillis();
            return baseSim.plusMillis(Math.round(realMillis * speed));
        }
    }

    private final Clock realClock;
    private final ZoneId zone;
    private volatile State state;

    public SimulatedClock(Clock realClock) {
        this(realClock, realClock.getZone(), new State(realClock.instant(), realClock.instant(), 1));
    }

    private SimulatedClock(Clock realClock, ZoneId zone, State state) {
        this.realClock = realClock;
        this.zone = zone;
        this.state = state;
    }

    @Override
    public Instant instant() {
        return state.simAt(realClock.instant());
    }

    public State state() {
        return state;
    }

    public void apply(State newState) {
        this.state = newState;
    }

    /** 지금 시각을 기준점으로 다시 잡고 배속을 바꾼다 (시각이 튀지 않음) */
    public State withSpeed(double speed) {
        Instant real = realClock.instant();
        return new State(real, state.simAt(real), speed);
    }

    /** 시뮬레이션 시각을 앞으로 건너뛴다 */
    public State jump(Duration amount) {
        Instant real = realClock.instant();
        return new State(real, state.simAt(real).plus(amount), state.speed());
    }

    /** 실제 시각으로 되돌린다 (배속 1) */
    public State reset() {
        Instant real = realClock.instant();
        return new State(real, real, 1);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // 같은 상태를 공유하되 시간대만 다른 시계 (상태 변경이 함께 반영되도록 위임)
        SimulatedClock self = this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
            @Override public Instant instant() { return self.instant(); }
        };
    }
}
