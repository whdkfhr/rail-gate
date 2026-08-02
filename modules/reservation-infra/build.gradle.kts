plugins {
    `java-library`
}

// ---------------------------------------------------------------------------
// 예약 컨텍스트의 인프라 어댑터.
//
// 여기가 좌석 상태 전이의 실제 방어선이다.
// 도메인 모듈은 전이 규칙만 표현하고, 동시성 보장은 이 모듈의
// 조건부 UPDATE + InnoDB 행 잠금이 한다.
//
// seat_inventory 단일 좌석 수준의 I-8 방어는 이 모듈에서 완성됐다.
//   AVAILABLE -> HELD    (JdbcSeatHoldRepository)
//   HELD -> PAYING       (JdbcSeatPaymentRepository)
//   PAYING -> SOLD       (JdbcSeatPaymentRepository)
//
// 예매 흐름 전체의 정합성을 완성하기 위해 아래 불변식이 별도로 남아 있다.
// 이들은 I-8 의 하위 조건이나 완성 조건이 아니라 각각 독립된 불변식이다.
//   I-9   다좌석 전부-또는-전무
//   I-11  만료 스위퍼와 확정의 경쟁
//   I-12  1인당 좌석 상한
//   I-15  결제 승인 검증 (payment 테이블 / PG 연동)
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
