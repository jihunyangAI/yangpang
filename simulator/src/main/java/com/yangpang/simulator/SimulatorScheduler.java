package com.yangpang.simulator;

import com.yangpang.domain.sim.SimClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 실제 시간 5초마다 시뮬레이터를 돌린다 (관리자 화면에서 자동 실행을 끌 수 있음) */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SimulatorScheduler {

    private final DeliverySimulator simulator;
    private final SimClockService simClock;

    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    public void run() {
        if (!simClock.autoRun()) return;
        try {
            simulator.tick();
        } catch (RuntimeException e) {
            log.warn("[시뮬레이터] 실행 중 오류: {}", e.getMessage(), e);
        }
    }
}
