# ADR-016 — 액세스 토큰 검증 라이브러리

> 상태: **채택** (2026-10-30, Task 2J-B). [ADR-015](ADR-015-login-and-admission-tokens.md) D-A5 의 결정이다.
>
> 관련: [ADR-015](ADR-015-login-and-admission-tokens.md) §3, [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md),
> CLAUDE.md 절대 규칙 4·5, 규칙 33·34

---

## 1. 해결하려는 문제

reservation-service 가 ADR-015 §3 의 로그인 액세스 토큰(HS256 JWT)을 **요청마다 서명만으로** 검증해야 한다.
검증은 정합성 경로 앞단의 신원 판정이라 틀리면 남의 키 범위·quota 를 쓸 수 있다. 직접 구현하면

- 서명 비교의 타이밍 공격, `alg` 혼동(`none`·RS→HS), 헤더·claim 파싱의 경계 사례를 모두 스스로 막아야 하고
- Spring Security 의 필터 체인·인증 객체·예외 처리와 따로 놀게 된다

요청 중에 issuer 의 키를 원격 조회(JWK Set)하면 인증 서버 가용성에 좌석 API 가 묶인다 — 절대 규칙 4 와 같은 문제다.

## 2. 대안 비교

| | 대안 | 판정 |
|---|---|---|
| **A** | **Spring Security OAuth2 Resource Server 의 JWT 지원** (`spring-boot-starter-security-oauth2-resource-server` → `spring-security-oauth2-jose`, 내부적으로 Nimbus JOSE+JWT). `NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)` 로 **로컬 대칭키 검증** | **선택** |
| B | jjwt 등 별도 JWT 라이브러리 + 직접 만든 Spring Security 필터 | 기각 — 필터·인증 객체·오류 처리를 직접 연결해야 하고, Boot BOM 이 버전을 관리하지 않아 별도 버전을 카탈로그에 적어야 한다 |
| C | JWT 서명·파싱 직접 구현 | 기각 — 위 §1 의 위험을 스스로 떠안는다 |
| D | Resource Server + `issuer-uri`/`jwk-set-uri` 로 원격 키 조회 | 기각 — 요청·기동 경로가 원격 issuer 에 묶인다. HS256 공유키 모델(ADR-015 D-A2)과도 맞지 않는다 |

## 3. 선택 근거

- **Spring Boot 4.1 BOM 이 버전을 관리한다** (Spring Security 7.1, Nimbus 포함). 카탈로그에는 버전 없이 좌표만 등록한다 (규칙 5).
- **이것은 OAuth 로그인 연동이 아니다.** Resource Server 모듈의 이름에 OAuth2 가 들어 있지만, 쓰는 것은 "Bearer JWT 를 받아 검증하는
  필터" 뿐이다. `oauth2Login`·인가 서버·클라이언트 등록·외부 IdP 는 쓰지 않는다 (P-13: OAuth 미도입).
- `issuer-uri`·`jwk-set-uri` 를 설정하지 않고 `JwtDecoder` 빈을 직접 만든다 — 원격 조회가 없다.
- `macAlgorithm(HS256)` 으로 서명 알고리즘을 고정하고, Nimbus 의 `JOSEObjectTypeVerifier` 로 `typ=at+jwt` 를 강제하며,
  나머지 claim 규칙(issuer·audience·`token_use`·필수 claim·수명·시계 오차)은 `OAuth2TokenValidator` 로 붙인다.

## 4. 포기하는 것

- Spring Security 전체가 들어온다 — 필터 체인이 모든 요청에 끼므로 **기본 거부** 규칙과 경로 예외를 명시적으로 관리해야 한다.
- 기본 `BearerTokenAuthenticationEntryPoint` 는 `WWW-Authenticate` 에 오류 설명을 담는다. 예외 메시지 노출을 막으려고
  **직접 만든 진입점**으로 바꾼다 (고정 `Bearer`).
- 라이브러리 기본 claim 변환(`sub` 를 문자열로 바꾸는 등)이 타입 검사를 가릴 수 있어 일부 claim 은 변환을 끈다.
- Spring Security 메이저 업그레이드 시 설정 DSL 변경을 따라가야 한다.
