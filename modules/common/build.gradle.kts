plugins {
    `java-library`
}

// 전 모듈이 공유하는 최소 공용 타입만 둔다.
// (에러 코드, 식별자, Clock 추상화 등)
//
// 주의: 여기에 Spring/JPA/Redis 의존성을 추가하지 않는다.
// common 이 무거워지면 순수 도메인 모듈까지 오염된다.
dependencies {
}
