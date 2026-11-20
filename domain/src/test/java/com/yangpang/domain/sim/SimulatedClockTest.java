package com.yangpang.domain.sim;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedClockTest {

    /** 테스트용으로 손으로 움직이는 실제 시계 */
    static class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void 배속1이면_실제_시각과_같다() {
        ManualClock real = new ManualClock();
        SimulatedClock sim = new SimulatedClock(real);
        real.advance(Duration.ofMinutes(5));
        assertThat(sim.instant()).isEqualTo(real.instant());
    }

    @Test
    void 배속60이면_실제_1분이_1시간() {
        ManualClock real = new ManualClock();
        SimulatedClock sim = new SimulatedClock(real);
        Instant start = sim.instant();
        sim.apply(sim.withSpeed(60));
        real.advance(Duration.ofMinutes(1));
        assertThat(Duration.between(start, sim.instant())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void 배속을_바꿔도_시각이_튀지_않는다() {
        ManualClock real = new ManualClock();
        SimulatedClock sim = new SimulatedClock(real);
        sim.apply(sim.withSpeed(60));
        real.advance(Duration.ofMinutes(10));          // 시뮬레이션 10시간 경과
        Instant before = sim.instant();
        sim.apply(sim.withSpeed(1));                    // 배속 변경 순간
        assertThat(sim.instant()).isEqualTo(before);
        real.advance(Duration.ofMinutes(1));
        assertThat(Duration.between(before, sim.instant())).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void 건너뛰기와_되돌리기() {
        ManualClock real = new ManualClock();
        SimulatedClock sim = new SimulatedClock(real);
        sim.apply(sim.jump(Duration.ofDays(1)));
        assertThat(sim.instant()).isEqualTo(real.instant().plus(Duration.ofDays(1)));
        sim.apply(sim.reset());
        assertThat(sim.instant()).isEqualTo(real.instant());
    }

    @Test
    void 시간대를_바꾼_시계도_같은_상태를_따른다() {
        ManualClock real = new ManualClock();
        SimulatedClock sim = new SimulatedClock(real);
        Clock kst = sim.withZone(ZoneId.of("Asia/Seoul"));
        sim.apply(sim.jump(Duration.ofHours(3)));
        assertThat(kst.instant()).isEqualTo(sim.instant());
    }
}
