// 웹 애플리케이션: 고객 화면 / 셀러센터 / 관리자 (Thymeleaf + htmx)
import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("org.springframework.boot")
    id("com.github.node-gradle.node") version "7.1.0"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":simulator"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("com.anthropic:anthropic-java:3.3.0")   // M4 ⑧ AI 리뷰 요약·챗봇 (API 키 없으면 꺼짐)
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

// ---- Tailwind CSS ----
// Gradle이 Node.js를 프로젝트 안(.gradle/nodejs)에 내려받아 Tailwind CLI를 실행한다 (따로 설치 불필요).
// Tailwind 단일 실행 파일(Standalone)은 서명이 없어 Windows 스마트 앱 컨트롤에 막히므로 이 방식을 쓴다.
node {
    download = true
    version = "26.13.1"
}

val buildCss = tasks.register<NpmTask>("buildCss") {
    description = "Tailwind로 static/css/app.css를 생성합니다"
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "build:css")
    inputs.file("src/main/tailwind/app.css")
    inputs.dir("src/main/resources/templates")
    inputs.file("src/main/resources/static/js/app.js")
    outputs.file("src/main/resources/static/css/app.css")
}

tasks.named("processResources") { dependsOn(buildCss) }

// 개발 중 템플릿·정적 파일을 src에서 바로 읽어 수정 즉시 반영 (재시작 불필요)
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    sourceResources(sourceSets["main"])
}
