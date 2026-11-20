package com.yangpang.domain.sim;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** 시뮬레이션 시계 설정을 DB(sim_setting)에 저장하고, 앱이 다시 시작돼도 이어지게 한다 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimClockService {

    private final SimulatedClock clock;
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        jdbc.query("select base_real, base_sim, speed from sim_setting where id = 1", rs -> {
            clock.apply(new SimulatedClock.State(rs.getTimestamp("base_real").toInstant(),
                    rs.getTimestamp("base_sim").toInstant(), rs.getDouble("speed")));
        });
        log.info("시뮬레이션 시계: {} (배속 {}x)", clock.instant(), clock.state().speed());
    }

    public Instant now() {
        return clock.instant();
    }

    public double speed() {
        return clock.state().speed();
    }

    public boolean autoRun() {
        Boolean v = jdbc.queryForObject("select auto_run from sim_setting where id = 1", Boolean.class);
        return Boolean.TRUE.equals(v);
    }

    public void setAutoRun(boolean value) {
        jdbc.update("update sim_setting set auto_run = ? where id = 1", value);
    }

    public void setSpeed(double speed) {
        if (speed <= 0 || speed > 100_000) throw new IllegalArgumentException("배속은 0보다 크고 100000 이하");
        save(clock.withSpeed(speed));
    }

    public void jump(Duration amount) {
        if (amount.isNegative()) throw new IllegalArgumentException("과거로는 갈 수 없습니다");
        save(clock.jump(amount));
    }

    public void reset() {
        save(clock.reset());
    }

    private void save(SimulatedClock.State s) {
        clock.apply(s);
        jdbc.update("update sim_setting set base_real = ?, base_sim = ?, speed = ? where id = 1",
                Timestamp.from(s.baseReal()), Timestamp.from(s.baseSim()), s.speed());
    }
}
