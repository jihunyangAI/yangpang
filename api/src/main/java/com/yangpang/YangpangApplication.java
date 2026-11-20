package com.yangpang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Yangpang 웹 애플리케이션 (연구용 데모).
 * com.yangpang 패키지에 두어 domain 모듈의 엔티티·리포지토리까지 자동으로 스캔한다.
 * 실행: ./gradlew :api:bootRun  →  http://localhost:8090
 */
@SpringBootApplication
public class YangpangApplication {

    public static void main(String[] args) {
        SpringApplication.run(YangpangApplication.class, args);
    }
}
