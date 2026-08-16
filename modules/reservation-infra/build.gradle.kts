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
// I-9 다좌석 전부-또는-전무가 확보됐다.
//   여러 좌석 -> HELD    (JdbcMultiSeatHoldRepository, 벌크 UPDATE + 조건부 실패)
//   Task 2C 시점에는 저장소가 단독 호출되는 범위에서만 확보됐고, 외부 트랜잭션 참여
//   동작은 미검증이었다. Task 2F 에서 그 위험을 재현한 뒤 계약을 바꿨다. 지금은:
//     - 저장소가 최상위 트랜잭션을 만들지 않는다.
//     - 호출자가 연 동일 DataSource 의 트랜잭션에 참여한다.
//     - 자신이 실행한 벌크 UPDATE 의 로컬 원자성을 위해 NESTED savepoint 경계는 만든다.
//     - 경합은 SeatUnavailableException 으로 전달한다.
//     - 활성 트랜잭션이 없으면 UPDATE 전에 거부한다.
//
// I-10 만료 회수와 I-11 만료-확정 경쟁도 저장소 수준에서 확보됐다.
//   HELD/PAYING -> AVAILABLE  (JdbcSeatExpiryRepository, 후보 SELECT + 조건부 벌크 UPDATE)
//   단, 주기 실행 배선(@Scheduled)이 없어 실제로 호출하는 주체는 아직 없다.
//
// 예매 흐름 전체의 정합성을 완성하기 위해 아래 불변식이 별도로 남아 있다.
// 이들은 I-8 의 하위 조건이나 완성 조건이 아니라 각각 독립된 불변식이다.
//   I-12  1인당 좌석 상한 (user_hold_quota 테이블 자체가 없다)
//         구현할 때는 선점 시 증가뿐 아니라 SOLD 확정, 만료 회수, 자발적 해제에 대응하는
//         감소를 같은 트랜잭션 경계에서 연동해야 한다.
//         확정과 만료 상태 전이는 이미 있지만 quota 연동이 없고,
//         자발적 해제도 저장소 CAS 까지는 구현됐다 (JdbcSeatReleaseRepository).
//         즉 세 이탈 경로의 상태 전이는 모두 갖춰졌고 quota 연동만 남았다.
//         다만 REST API 와 멱등키 저장소가 없어 FR-2.5 자체는 완성되지 않았다.
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
