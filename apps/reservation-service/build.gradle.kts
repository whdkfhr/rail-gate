plugins {
    java
    alias(libs.plugins.spring.boot)
}

// ---------------------------------------------------------------------------
// Reservation Service — Spring MVC
//
// WebFlux 를 쓰지 않는 이유:
//   JDBC 는 근본적으로 블로킹이다. 블로킹 호출을 이벤트 루프에서 실행하면
//   루프가 막혀 애플리케이션 전체가 멈춘다.
//   또한 이 서비스의 부하는 대기열의 1/100 수준이므로 논블로킹의 이득이 없다.
//
// 이 서비스가 시스템의 유일한 진실의 원천(MySQL)을 다룬다.
// 좌석 정합성 관련 모든 로직이 여기에 모인다.
// ---------------------------------------------------------------------------
dependencies {
    implementation(project(":modules:reservation-domain"))
    implementation(project(":modules:reservation-infra"))
    implementation(project(":modules:queue-token"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Task 2H-A. 선점 유스케이스가 트랜잭션을 소유하므로 앱이 DataSource 와
    // 트랜잭션 관리자를 구성한다. 저장소는 그 DataSource 를 주입받을 뿐이다.
    //
    // JPA 가 아니라 starter-jdbc 인 이유는 reservation-infra 와 같다 —
    // dirty checking 은 "조건부 UPDATE 한 번" 이라는 요구와 충돌한다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly(libs.mysql.connector)

    // Spring Boot 4 는 자동 구성을 모듈별로 쪼갰다. spring-boot-autoconfigure 에
    // Flyway 자동 구성이 더 이상 들어 있지 않으므로 명시해야 한다.
    // 없으면 컨텍스트는 뜨지만 마이그레이션이 조용히 실행되지 않는다 —
    // 실제로 "Table 'railgate.seat_inventory' doesn't exist" 로 관측했다.
    runtimeOnly("org.springframework.boot:spring-boot-flyway")

    // 통합 테스트는 실제 MySQL 로만 한다 (CLAUDE.md 규칙 26).
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.junit)

    // 아직 추가하지 않는 것: JPA, Kafka.
}
