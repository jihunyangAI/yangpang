// 가짜 데이터 생성기 (Datafaker). 실행: ./gradlew :seed:bootRun
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":domain"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("net.datafaker:datafaker:2.4.3")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // 예: ./gradlew :seed:bootRun --args="--seed.profile=mvp"
    workingDir = projectDir
}
