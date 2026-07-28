plugins {
    `java-library`
}

// 대기열 순수 도메인.
//
// 규칙: main 소스에 Spring / Redis / 네트워크 의존성을 두지 않는다.
// 이유: 순번 계산과 상태 전이 로직을 Redis 없이 단위 테스트할 수 있어야 하고,
//       나중에 대기열을 샤딩할 때 인프라만 교체하고 도메인은 건드리지 않기 위함이다.
dependencies {
    api(project(":modules:common"))
}
