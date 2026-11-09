# TASK-002J-B — 계약 결정 반영과 액세스 토큰 검증

> 관련: [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md) (v0.2), [ADR-015](../adr/ADR-015-login-and-admission-tokens.md) (부분 채택),
> [ADR-016](../adr/ADR-016-access-token-validation-library.md) (채택), [ADR-009](../adr/ADR-009-idempotency-key.md),
> [TASK-002J-A](TASK-002J-A-hold-http-contract.md), CLAUDE.md 규칙 21·24·33·34, INVARIANTS I-7·I-12·I-18

## 1. 목적과 범위

두 커밋으로 나눴다.

1. **문서 커밋** (`0fc3473`) — PR #31 리뷰 보완과 승인된 결정(D1~D4, D-A2·D-A3)을 규칙·ADR·계약에 반영했다.
2. **구현 커밋** (이 기록과 같은 커밋) — reservation-service 에 Spring Security 기반 **로그인 액세스 토큰 검증**(ADR-015 §3)과
   **요청별 traceId** 를 넣었다. 아래 §3~§5 는 그 커밋의 내용과 검증 결과다.

**하지 않은 것** — 입장 토큰 검증(ADR-015 §4), 두 토큰의 사용자 대조(§5), 선점 컨트롤러, 로그인 발급기·개발 전용 발급기,
사용자 테이블, migration, D4(TX1 안 회차 재대조) 구현. 이 경로들은 아직 없다.

---

## 2. 결정과 리뷰 보완 (문서 커밋 `0fc3473`)

| 항목 | 결과 |
|---|---|
| **D1** | **B 채택** — 재생 대상의 상태 코드·본문 바이트·`Location`·`Content-Type` 유지. 모든 응답에 현재 요청의 `X-Trace-Id`. 재생 대상 업무 실패 본문은 `{ code, message }` 만(규칙 24 예외), 재생 대상이 아닌 오류는 본문에도 traceId |
| D1 리뷰 보완 | **배포 간 응답 호환 조건** — 결정적 생성이어도 배포 후 문구·상태·직렬화가 바뀌면 바이트 동일성이 깨진다. 재생 가능한 키가 남는 동안 호환 유지, 자동 삭제가 없으므로 기간 경과를 가정하지 않음, 바꾸려면 응답 버전 보존 또는 원본 저장 전략을 먼저 채택 |
| **D2** | 채택 — 성공 본문에 `expiresAt` 없음, 후속 홀드 조회로 |
| **D3** | 채택 — 상한 초과 = **409 `QUOTA_EXCEEDED`**, `Retry-After` 강제 안 함, 상한 해소 뒤 재시도는 새 키. 규칙 21·I-12 문구 정합. 진행 중의 `Retry-After` 와 일반 429 규약은 유지 |
| **D4** | 채택(설계) — 기대 회차를 명령에 실어 TX1 안에서 재대조, fingerprint 영향 없음(`hold-seats/v1` 유지), 403 비저장. 구현은 선점 HTTP 연결 때 |
| **D-A2** | 채택 — MVP 는 HS256 + 용도별 키 분리 |
| **D-A3** | 채택 — 입장 `evt` = `sale_event.id` 식별자 공간 |
| 개발 발급 리뷰 보완 | 활성 조건 정정 — `enabled=true` + **명시적 활성 프로필 1개 이상** + **모든** 프로필이 `local`/`loadtest`. 무프로필·`prod`·`local+prod` 거부, `local`·`loadtest` 허용 인수 시나리오 추가. 발급기는 미구현 |
| 미결정 유지 | D-A1(발급 신원 확인), D-A4(수명·시계 오차 수치), C4(리스·보존) |

---

## 3. 구현 (구현 커밋 — 이 기록과 같은 커밋)

### 3.1 라이브러리 — ADR-016

`spring-boot-starter-security-oauth2-resource-server`(Boot 4.1 BOM 관리, Spring Security 7.1, Nimbus JOSE+JWT 10.9).
카탈로그에 버전 없이 등록했다. **OAuth 로그인 연동이 아니다** — Bearer JWT 를 로컬 대칭키로 검증하는 필터만 쓴다.
`issuer-uri`·`jwk-set-uri` 를 쓰지 않아 요청·기동 중 원격 조회가 없다.

### 3.2 구성 요소

| 파일 | 역할 |
|---|---|
| `config/AccessTokenDecoderConfig` | `NimbusJwtDecoder` — HS256 고정, `typ=at+jwt` 강제, 서명키 기동 검증, 시계 빈 |
| `api/security/AccessTokenProperties` | `railgate.auth.access-token.*` — `secret`(기본값 없음), `issuer`, `audience`, `clock-skew`(30초, 가정), `max-lifetime`(30분, 가정) |
| `api/security/AccessTokenValidator` | `iss`·`aud`·`token_use=access`·`sub`(문자열 양수 long)·`jti`·`iat`/`exp` 필수·미래 시각·수명·`nbf` |
| `api/security/AuthenticatedUser`·`AccessTokenAuthentication` | 인증 주체 — `@AuthenticationPrincipal AuthenticatedUser`. 토큰 원문을 보관하지 않는다 |
| `api/security/JsonAuthenticationEntryPoint`·`JsonAccessDeniedHandler`·`ErrorResponses` | 401(`WWW-Authenticate: Bearer`)·403 고정 본문 `{ code, message, traceId }` |
| `config/WebSecurityConfig` | 상태 없는 필터 체인. **기본 거부** |
| `api/trace/TraceIdFilter`·`config/TraceConfig` | traceId 생성·`X-Trace-Id`·MDC·요청 속성, 최고 우선순위로 등록 |

### 3.3 경로 규칙

| 경로 | 토큰 없음 | 유효한 토큰 |
|---|---|---|
| `GET /actuator/health` | 200 (상세 비노출 — `show-details: never` 로 바꿨다) | 200 |
| `/api/**` | 401 | 통과 (현재 매핑된 운영 컨트롤러는 없다 → 404) |
| 그 밖 (`/actuator/metrics`·`info`·`/actuator`·미매핑 경로) | 401 | **403** (기본 거부) |

### 3.4 판단 근거

- **`sub`·`jti` 타입은 원래 JSON 값으로 본다.** Nimbus 가 숫자 `sub`(예: `4242`)를 문자열 `"4242"` 로 정규화해 넘겨서,
  첫 GREEN 시도에서 "sub 가 숫자 타입이면 401" 테스트가 실패했다(200). 서명 검증을 마친 토큰의 페이로드를 같은 라이브러리
  (`SignedJWT.parse(...).getPayload().toJSONObject()`)로 다시 읽어 원래 타입을 확인한다. 직접 파싱하지 않는다.
- **401 의 `WWW-Authenticate` 는 고정 `Bearer`** — 기본 진입점은 `error_description` 에 예외 메시지를 싣는다.
- **traceId 는 항상 새로 만든다** — 클라이언트가 보낸 `X-Trace-Id` 를 쓰지 않는다(로그 위조·충돌 방지).
- **traceId 필터는 Filter 빈이 아니라 등록 빈 하나** — Filter 빈이면 Boot 가 자동 등록해 두 번 걸릴 수 있다. 우선순위는
  `HIGHEST_PRECEDENCE` 로 보안 필터(-100)보다 앞이라 401·403 응답에도 traceId 가 있다.
- **토큰 시각은 애플리케이션 시계** — DB 를 거치지 않고 검증해야 하므로 규칙 7 의 대상이 아니다(ADR-015 §3). 주입 가능한 `Clock` 이다.

---

## 4. ★ TDD 기록

| 단계 | 대상 | 결과 |
|---|---|---|
| RED | 의존성만 추가하고 `TraceIdFilter`·`AccessTokenDecoderConfig` 를 골격으로 둔 상태 — Spring Boot 기본 보안(HTTP Basic, 생성 비밀번호) | **새 테스트 59개 중 55개 실패**. 주요 사유: `WWW-Authenticate` 가 `Basic realm="Realm"` (34), 키 기동 검증 없음(7), 정상 토큰 401(5), 403 기대에 401(3), traceId 헤더·MDC 없음, health 상세 노출 |
| GREEN 1차 | 구현 | 58/59 — **숫자 `sub` 가 통과**(위 §3.4) |
| GREEN | 원래 JSON 타입 검사 추가 | **59/59** |

RED 에서 통과한 4개는 골격 필터의 MDC 정리·예외 시 정리처럼 "아무것도 넣지 않으면 남지도 않는" 성질과 컨텍스트 구성 확인이다.

### 테스트 구성

- **`AccessTokenAuthenticationTest` (47개)** — **실제 SecurityFilterChain**: `@SpringBootTest(RANDOM_PORT)` 내장 서버에 HTTP 로 요청한다
  (실제 필터 등록·순서를 그대로 탄다). MySQL Testcontainers 는 기존 지원 클래스를 쓴다. 확인용 컨트롤러는 컴포넌트 스캔 밖 테스트 패키지
  (`com.railgate.testprobe`)에 두고 이 테스트만 `@Import` 로 등록한다. 시계는 `@Primary MutableClock` 으로 옮긴다(`Thread.sleep` 없음).
  - §1 정상(3): 사용자 id → 인증 주체, `X-Trace-Id` 1개·32자 16진수·컨트롤러 안 MDC 와 같음, 요청마다 다름, `aud` 배열 허용
  - §2 서명(8): 누락·변조·다른 키·`alg=none`·HS512·RS256·형식 아님·쿼리 문자열 토큰 → 401
  - §3 용도(7): issuer 다름·개발 issuer(`railgate-dev-auth`)·audience 없음/다름·`typ` JWT/admission+jwt/없음·`token_use` admission/없음, **입장 토큰(다른 키)·같은 키로 서명된 입장 토큰 형식** → 401
  - §4 필수 claim(14): `sub`·`iat`·`exp`·`jti` 누락, `sub` = `abc`·`0`·`-1`·`01`·`1.5`·` 7`·long 초과·숫자 타입, `jti` 빈 값·숫자, 시각 claim 문자열 → 401
  - §5 시간(4): exp+30초 통과·+31초 401, iat 미래 30초 통과·31초 401, nbf 과거 통과·미래 31초 401, 수명 30분 통과·30분 1초 401·exp ≤ iat 401
  - §6 경로(11): health 공개·상세 없음, metrics·info·actuator·미매핑·`/api/reservations/holds` 토큰 없이 401, api 밖 경로 토큰 있어도 403 고정 본문, traceId 필터 단일 등록, 시계 주입
  - 모든 401 은 `WWW-Authenticate: Bearer` 하나, JSON `{ code: AUTHENTICATION_REQUIRED, message: 고정, traceId = 헤더 }`, 보낸 토큰·"jwt"·"exception" 문자열 미포함을 함께 확인한다
- **`AccessTokenKeyStartupTest` (7개)** — `ApplicationContextRunner` 로 실제 바인딩·컨텍스트 시작: 키 없음·빈 값·공백·Base64 아님·32바이트 미만·한 바이트 반복 → 기동 실패, 오류 메시지에 키 값 없음
- **`TraceIdFilterTest` (5개)** — MDC·요청 속성·헤더 일치, 요청 종료·예외 시 MDC 제거, 이전 요청 값 미누출, 클라이언트 `X-Trace-Id` 미사용

기존 MySQL Spring 테스트는 `MySqlSpringTestSupport` 의 `@DynamicPropertySource` 가 **테스트 전용 키**를 넣는다. 테스트용 키·발급 도우미
(`TestAccessTokens`)·`MutableClock`·확인용 컨트롤러는 모두 테스트 소스에 있다.

---

## 5. 전체 검증 기록

```bash
# 이전 실행의 XML 을 모두 지운 뒤 전체를 다시 돌렸다 (남은 결과와 합산하지 않기 위해)
find . -path '*/build/test-results/test' -type d -exec rm -rf {} +
git diff --check
./gradlew check --rerun-tasks --max-workers=1
```

`BUILD SUCCESSFUL in 1m 48s` — `21 actionable tasks: 21 executed` (`--rerun-tasks` 라 UP-TO-DATE 없음).
`git diff --check` 는 공백 오류 없음.

### 모듈별 결과 — 이번 실행의 XML 만 집계

| 모듈 | `test` 실행 | 클래스 | 테스트 | 실패 | 오류 | 스킵 |
|---|---|---:|---:|---:|---:|---:|
| `apps/queue-service` | O | 1 | 1 | 0 | 0 | 0 |
| `apps/reservation-service` | O | 97 | 348 | 0 | 0 | 0 |
| `modules/reservation-domain` | O | 32 | 202 | 0 | 0 | 0 |
| `modules/reservation-infra` | O | 146 | 621 | 0 | 0 | 0 |
| `modules/common`·`queue-domain`·`queue-token` | — | 0 | 0 | 0 | 0 | 0 |
| **합계** | | **276** | **1,172** | **0** | **0** | **0** |

`modules/common`·`queue-domain`·`queue-token` 은 `check` 태스크는 실행됐지만 **테스트 소스가 0개**라 `test` 가 NO-SOURCE 다.
"테스트 0건 통과" 가 아니라 "검증할 테스트가 없다" 는 뜻이다. 루트 프로젝트의 `verifyArchitectureRules`(도메인 순수성·모듈 경계·
외부 의존성 버전 직접 명시 금지)도 이번 실행에 포함됐다 — 새 의존성을 카탈로그에만 등록한 것이 여기서 검증된다.

### 이번 작업이 추가한 테스트 (59개, 전부 통과)

| 클래스 / 중첩 | 테스트 | 시간 |
|---|---:|---:|
| `AccessTokenAuthenticationTest` §1 정상 토큰 | 3 | 0.041s |
| `AccessTokenAuthenticationTest` §2 누락·변조·잘못된 알고리즘·키 | 8 | 0.288s |
| `AccessTokenAuthenticationTest` §3 issuer·audience·typ·token_use | 7 | 0.084s |
| `AccessTokenAuthenticationTest` §4 필수 claim 의 존재와 타입 | 14 | 0.871s |
| `AccessTokenAuthenticationTest` §5 만료·미래 시각·수명·시계 오차 | 4 | 0.068s |
| `AccessTokenAuthenticationTest` §6 보호 경로·기본 거부·필터 등록 | 11 | 0.168s |
| `AccessTokenKeyStartupTest` | 7 | 0.037s |
| `TraceIdFilterTest` | 5 | 0.003s |

### 검증 중 고친 것

- **`[serial]` 컴파일 경고** — `AccessTokenAuthentication` 의 `AuthenticatedUser` 필드가 직렬화 불가 타입이라는 경고가 났다.
  필터 체인이 `STATELESS` 라 보안 컨텍스트를 세션에 저장하지 않으므로 직렬화 경로가 없다. `transient` 로 두면 나중에 세션 저장을
  켰을 때 조용히 `null` 이 되므로, 그 대신 `@SuppressWarnings("serial")` + 사유 주석으로 남겼다 — 그때는 `NotSerializableException`
  으로 즉시 드러난다. 재실행 후 경고는 사라졌다.

### 환경 — 측정의 전제

처음 실행은 **코드 결함이 아니라 환경 문제로 완주하지 못했다.** MySQL Testcontainer(`mysql:8.4`)가
`exit=137`·`OOMKilled=true` 로 죽었고 실패는 전부 `Communications link failure`·`Connection refused` 였다 (**단정 실패 0건**).
Docker VM 이 1.928 GiB 인데 다른 프로젝트 컨테이너 5개가 약 404 MiB 를 쓰고 있었다. 사용자 승인을 받아 그 컨테이너들을 중지한 뒤
재실행해 위 결과를 얻었다. 이 기록의 수치는 **중지 후 실행 1회**의 것이다 — 실패한 실행의 부분 결과와 합산하지 않았다.

---

## 6. 남은 것과 위험

| 항목 | 상태 |
|---|---|
| 입장 토큰 검증·사용자 대조·입장 회차 대조 (ADR-015 §4·§5) | 없다 — Task 2J-C |
| 선점 컨트롤러·헤더·본문 검증·결과 매핑·D4 | 없다 — Task 2J-D |
| 로그인 발급·개발 전용 발급·사용자 테이블 | 없다 — Task 2J-E, D-A1 결정 대기 |
| 토큰 폐기(blocklist) | 없다 — 만료 전까지 유효 |
| 수명 30분·시계 오차 30초 | 가정값 (D-A4) |
| JSON 구조화 로그(규칙 33) | 없다 — MDC 에 traceId 만 넣었다. 로그 패턴·`userId` MDC 는 후속 |

- 운영 키 배포 방식(환경 변수·비밀 저장소)은 정하지 않았다. 저장소·설정 파일에는 키가 없고, 테스트 키는 테스트 소스에만 있다.
- HS256 대칭키라 검증자도 서명할 수 있다 (ADR-015 §7).
- 오류 디스패치(`DispatcherType.ERROR`)는 허용했다 — 원래 요청에서 이미 인증 판정을 마친 뒤의 오류 페이지 렌더링이다. 오류 디스패치
  시점에는 MDC 의 traceId 가 이미 지워져 있을 수 있다(요청 속성·응답 헤더에는 남는다).
