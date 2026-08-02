plugins {
    `java-library`
}

// ---------------------------------------------------------------------------
// 예약 컨텍스트의 인프라 어댑터.
//
// 여기가 I-8 을 완성하기 위한 좌석 선점 단계의 실제 방어선이다.
// 도메인 모듈은 상태 전이 규칙만 표현하고, 동시성 보장은 이 모듈의
// 조건부 UPDATE + InnoDB 행 잠금이 한다.
//
// I-8("최종적으로 한 예약에만 확정") 은 확정 단계
// PAYING -> SOLD ... WHERE hold_id = ? 조건부 UPDATE 와 함께 완결된다. 후속 Task 다.
//
// 규칙:
//  - reservation-domain 에만 의존한다. 다른 모듈을 끌어오지 않는다.
//  - JPA 를 쓰지 않는다. dirty checking 은 "조건부 UPDATE 한 번" 이라는 요구와 충돌한다.
//  - 임베디드 DB 를 쓰지 않는다. 행 잠금 동작이 실제 MySQL 과 달라
//    동시성 테스트가 아무것도 증명하지 못한다 (CLAUDE.md 규칙 26).
// ---------------------------------------------------------------------------
dependencies {
    api(project(":modules:reservation-domain"))

    implementation(libs.spring.jdbc)

    runtimeOnly(libs.mysql.connector)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.mysql)

    // 테스트 픽스처용. 운영 DataSource 는 앱 모듈이 구성한다.
    testImplementation(libs.hikaricp)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.mysql.connector)
}
