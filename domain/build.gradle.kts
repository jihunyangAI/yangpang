// 엔티티, 리포지토리, 핵심 비즈니스 로직, DB 마이그레이션(Flyway)
plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // QueryDSL (OpenFeign 유지보수 포크: Hibernate 7 / Jakarta 지원). Q클래스는 빌드 시 자동 생성
    api("io.github.openfeign.querydsl:querydsl-jpa:7.7")
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:7.7:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
}
