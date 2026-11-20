package com.yangpang.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param scale           데이터 규모 (기획서 7.2)
 * @param randomSeed      고정 시드. 같은 값이면 항상 같은 데이터가 생성된다
 * @param reset           true면 기존 데이터를 모두 지우고 다시 만든다
 * @param defaultPassword 모든 가짜·테스트 계정의 개발용 비밀번호
 */
@ConfigurationProperties(prefix = "seed")
public record SeedProperties(
        SeedScale scale,
        long randomSeed,
        boolean reset,
        String defaultPassword
) {
    public SeedProperties {
        if (scale == null) scale = SeedScale.MVP;
        if (defaultPassword == null) defaultPassword = "yangpang1234!";
    }
}
