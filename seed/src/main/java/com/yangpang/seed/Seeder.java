package com.yangpang.seed;

/**
 * 데이터 종류별 생성기. 기능을 붙이는 단계마다 생성기를 추가한다 (기획서 7.1).
 * 실행 순서는 {@link org.springframework.core.annotation.Order}로 정한다.
 */
public interface Seeder {

    String name();

    void seed(SeedContext ctx);
}
