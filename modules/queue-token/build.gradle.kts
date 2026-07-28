plugins {
    `java-library`
}

// ---------------------------------------------------------------------------
// 입장 토큰(Admission Token) — queue-service 와 reservation-service 의 유일한 접점.
//
// 이 모듈이 존재하는 이유:
//   reservation-service 가 "이 사용자 입장했나요?" 를 queue-service 에 HTTP 로 물으면
//   좌석 API 전체가 대기열 서비스의 가용성에 종속된다.
//   대신 서명된 토큰을 검증하게 하여 서비스 간 동기 호출을 0회로 만든다.
//
// 규칙: 이 모듈은 두 앱이 함께 의존하는 유일한 모듈이다.
//       queue-* 와 reservation-* 는 서로를 직접 참조하지 않는다.
// ---------------------------------------------------------------------------
dependencies {
    api(project(":modules:common"))
}
