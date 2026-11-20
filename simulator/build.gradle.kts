// 시뮬레이터 (기획서 7.4): 배송 자동 진행, 자동 구매확정, 미결제 주문 취소.
// 웹 앱(api)과 같은 프로세스에서 스케줄 작업으로 돈다.
plugins {
    `java-library`
}

dependencies {
    api(project(":domain"))
}
