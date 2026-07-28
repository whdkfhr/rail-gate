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
    implementation(project(":modules:queue-token"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // 아직 추가하지 않는 것: JPA, MySQL 드라이버, Flyway, Kafka, Testcontainers.
    // TASK-001 에서 좌석 선점을 구현할 때 추가한다.
}
