plugins {
    `java-library`
}

// 좌석 / 예약 / 결제 순수 도메인.
//
// 규칙: main 소스에 Spring / JPA / JDBC 의존성을 두지 않는다.
// 이유: 좌석 상태 머신과 전이 규칙은 이 프로젝트에서 가장 중요한 로직이므로
//       DB 없이 빠르게 반복 테스트할 수 있어야 한다.
dependencies {
    api(project(":modules:common"))
}
