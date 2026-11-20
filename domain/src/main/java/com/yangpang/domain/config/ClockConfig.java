package com.yangpang.domain.config;

import com.yangpang.domain.sim.SimulatedClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 앱 전체가 쓰는 시계 = 시뮬레이션 시계 (기획서 7.4).
 * 처음에는 실제 시각과 같고, 관리자 시뮬레이터 화면에서 배속·건너뛰기를 바꾼다.
 */
@Configuration
public class ClockConfig {

    @Bean
    SimulatedClock clock() {
        return new SimulatedClock(Clock.systemDefaultZone());
    }
}
