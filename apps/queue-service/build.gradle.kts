plugins {
    java
    alias(libs.plugins.spring.boot)
}

// ---------------------------------------------------------------------------
// Queue Service — Spring WebFlux / Reactor Netty
//
// WebFlux 를 쓰는 이유는 "논블로킹이 빨라서" 가 아니라,
// 순번 스트림(SSE)을 수십만 커넥션 규모로 유지해야 하기 때문이다.
// 플랫폼 스레드 모델로는 커넥션당 스택 비용 때문에 불가능하다.
//
// 이 선택은 Phase 6 에서 MVC + Virtual Thread 구현과 동일 부하로 비교 측정하여
// 근거를 수치로 남긴다. (docs/ARCHITECTURE.md 참고)
// ---------------------------------------------------------------------------
dependencies {
    implementation(project(":modules:queue-domain"))
    implementation(project(":modules:queue-token"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // 아직 추가하지 않는 것: Redis, Kafka, JPA.
    // 해당 Phase 에서 필요해질 때 추가한다.
}
