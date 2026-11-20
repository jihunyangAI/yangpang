package com.yangpang.seed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 가짜 데이터 생성기.
 * 실행: ./gradlew :seed:bootRun                       (MVP 규모, 기존 데이터 삭제 후 재생성)
 *      ./gradlew :seed:bootRun --args="--seed.scale=MVP --seed.random-seed=42"
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SeedApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SeedApplication.class, args)));
    }
}
