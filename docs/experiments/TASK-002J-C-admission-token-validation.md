# TASK-002J-C — 입장 토큰 검증과 두 토큰의 사용자 대조

> 관련: [ADR-015](../adr/ADR-015-login-and-admission-tokens.md) §4·§5, [ADR-016](../adr/ADR-016-access-token-validation-library.md),
> [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md) §3 단계 2~3, [INVARIANTS.md](../INVARIANTS.md) I-6·I-7, REQUIREMENTS FR-2.1,
> [TASK-002J-B](TASK-002J-B-access-token-validation.md) — 액세스 토큰 검증, CLAUDE.md 절대 규칙 3·4, 규칙 34·35

## 1. 범위

**한 것**

- `modules/queue-token` — 입장 토큰 claim 모델·검증기 (`AdmissionTokenVerifier`, `AdmissionTokenKey`, `AdmissionTokenPolicy`,
  `AdmissionClaims`, `AdmissionVerification`, `AdmissionRejection`). Spring·Servlet·reservation 도메인에 의존하지 않고
  Nimbus JOSE+JWT 만 쓴다. 네트워크 없이 동작하고 `Clock` 을 주입받는다
- reservation-service — `AdmissionTokenProperties`(`railgate.auth.admission-token.*`), `AdmissionTokenConfig`(키 품질·두 키 동일 거부),
  `AdmissionTokenFilter`(입장 검증 + 사용자 대조), `AdmissionContext`(요청 범위 입장 정보), 오류 코드 2개, 메트릭

**하지 않은 것** (Task 2J-D 이후) — 선점 운영 컨트롤러, 요청 좌석의 판매 회차 사전 해석과 `evt` 대조(HOLD_HTTP_CONTRACT §3 단계 6~7),
TX1 안 회차 재대조(D4), 토큰 발급기, 대기열 승격, blocklist. D-A1(발급 신원 확인)·D-A4(수명·시계 오차 수치)는 확정하지 않았다 —
10분·30초는 가정값 그대로다.

**확보한 보장 범위** — `POST /api/reservations/holds` 에 한해 "유효한 입장 토큰이 있고 그 사용자가 로그인 사용자와 같다" 까지다.
I-6(사용자 바인딩은 됨, 회차 바인딩은 아직)·I-7·FR-2.1 은 **완료가 아니다** — 운영 컨트롤러·회차 대조·다른 좌석 API·발급이 없다.

---

## 2. 설계 결정

### `evt` 의 표현 — 양수 `long` 10진 **문자열**

기존 계약 조사: ADR-015 §4 는 "`evt`=판매 회차 id" 만 정했고 타입은 없었다. D-A3 는 `sale_event.id`(MySQL `BIGINT`)와 같은 식별자 공간으로
정했다. Task 2J-B 의 테스트 도우미는 숫자를 썼지만 계약이 아니었다.

| 후보 | 판단 |
|---|---|
| JSON 숫자 | 기각 — `BIGINT` 는 2^53 을 넘을 수 있고, IEEE 754 로 숫자를 다루는 파서(JavaScript 등)는 정밀도를 잃는다. `9e6`·`9000000.0` 같은 표기도 같은 값으로 읽힐 수 있어 "원래 JSON 타입" 검사가 흐려진다 |
| **10진 문자열** | **선택** — `sub`(JWT 규격상 문자열)와 같은 규칙 하나로 검증한다: 앞자리 0 없는 1~19자리, `long` 범위의 양수 |

**문자열과 숫자를 함께 허용하지 않는다** — 숫자 타입 `evt` 는 거부한다(테스트로 고정). 발급자(queue-service, Phase 2)는 이 표현을 따라야 한다.

### 검증 순서와 사유 (`AdmissionRejection`)

형식(`MALFORMED`) → `alg=HS256`(`ALGORITHM`, 서명 없는 `alg=none` 포함) → `typ=admission+jwt`(`TYPE`) → 서명(`SIGNATURE`) →
`iss`·`aud`·`token_use`(`PURPOSE`) → 필수 claim 의 존재·원래 JSON 타입·범위(`CLAIMS`) → 수명(`LIFETIME`, `iat < exp`·10분) →
미래 시각(`NOT_YET_VALID`, `iat`·`nbf` > now+30초) → 만료(`EXPIRED`, now > exp+30초). 사유는 메트릭 태그로만 쓰고 응답은 언제나 같은 401 이다.

- `aud` 는 JWT 규격대로 문자열 하나 또는 문자열 배열을 받는다. 배열에 문자열이 아닌 원소가 있으면 거부
- `iat`·`exp`·`nbf` 는 **정수** JSON 숫자만 받는다 (소수·문자열 거부)
- 원래 타입은 서명 검증을 마친 페이로드를 Nimbus 로 읽은 JSON 값으로 본다 — 직접 파싱하지 않는다

### 키 — `K_admission`

기본값 없음. 액세스 키와 같은 품질 기준(Base64, 32바이트 이상, 한 바이트 반복 금지)을 queue-token 의 `AdmissionTokenKey` 가 적용한다.
앱 설정이 **디코딩한 바이트**로 `K_access` 와 비교해 같으면 기동을 막는다(Base64 표기만 달라도 같은 키로 본다). 상수 시간 비교.

### 필터 경계 — `AdmissionTokenFilter`

| 항목 | 정책 |
|---|---|
| 경로·메서드 | `POST /api/reservations/holds` 하나. health·`/api/reservations/_probe/*`(테스트)·입장 불필요 API 에는 요구하지 않는다 |
| 위치 | SecurityFilterChain 의 `AuthorizationFilter` **뒤** — 액세스 토큰이 없거나 틀리면 여기 오기 전에 401 `AUTHENTICATION_REQUIRED` |
| 디스패치 | REQUEST 만 (`OncePerRequestFilter` 기본 — ERROR·ASYNC 디스패치 건너뜀) |
| 등록 | 빈이 아니라 보안 체인에 `addFilterAfter` 로 한 번. 서블릿 컨테이너에 따로 등록되지 않는다(테스트로 확인) |
| 헤더 | `Admission-Token` 필드가 정확히 하나, 값이 비어 있지 않고 쉼표·공백이 없어야 한다. 아니면 401 |
| 401 | `ADMISSION_TOKEN_INVALID` + `WWW-Authenticate: Admission-Token`, 본문 `{ code, message, traceId }` (traceId = `X-Trace-Id`) |
| 403 | 두 토큰이 모두 유효한데 `sub` 가 다르면 `ADMISSION_USER_MISMATCH` |
| 통과 | `AdmissionContext(userId, saleEventId)` 를 요청 속성에 둔다. 로그인 principal(`AuthenticatedUser`)은 바꾸지 않는다 |
| 관측 | `railgate.auth.admission{outcome=accepted\|rejected\|user_mismatch, reason=none\|missing\|header\|<AdmissionRejection>}` — 고정 값만, 사용자·회차 id 없음 |

### 네트워크 무의존의 근거

- `modules/queue-token` 의 의존성은 `modules:common`(소스 없음)과 Nimbus JOSE+JWT 뿐이다. 검증기 생성자는 키·정책·시계만 받는다
- reservation-service 클래스패스에 Redis 클라이언트(Lettuce·Jedis·Spring Data Redis)가 없다 — 테스트로 확인
- 검증 경로에 HTTP 클라이언트 호출이 없다 (코드 확인). 위 두 가지는 구조적 근거이며 런타임 네트워크 차단 실험은 하지 않았다

---

## 3. ★ TDD 기록

| 단계 | 대상 | 결과 |
|---|---|---|
| RED 1 | queue-token — 타입·정책은 완성, 검증기·키는 골격 | **47개 중 45개 실패** (38개 `UnsupportedOperationException`, 키 품질 검사 없음 5개 등). 통과 2개는 정책 값 검증 |
| GREEN 1 | 검증기·키 구현 | 47/47 (테스트 도우미가 HS384 에 32바이트 키를 쓰던 것을 64바이트로 고침) |
| RED 2 | 앱 — 필터·설정 골격(필터는 그대로 통과, 설정은 빈 구성) | **30개 중 27개 실패**. 골격 필터는 입장 토큰 없이 컨트롤러까지 통과시켜 확인용 컨트롤러가 500(입장 정보 없음)을 냈다 |
| GREEN 2 | 설정·필터 구현 | 30/30 |
| 정리 | 전체 check 에서 `Too many connections` (§6) | 웹 보안 테스트의 컨텍스트 공유로 해결 |

### 테스트 (신규 77개)

- **`AdmissionTokenVerifierTest` (queue-token, 47개)** — 정상·`aud` 문자열/배열, 변조·키만 다름·`alg=none`·HS384·HS512·RS256·형식 오류·null,
  `typ` 3종·`iss`/`aud`/`token_use` 오류·같은 키로 서명한 액세스 토큰 모양, 필수 claim 5종 누락, `sub`·`evt` 범위 오류 9종·숫자 타입,
  `jti`·시각 claim 타입, 만료 +30/+31초·미래 발급 30/31초·`nbf`·수명 10분/10분 1초/`exp=iat`, 키 품질·`toString` 비노출·정책 검증
- **`AdmissionTokenStartupTest` (12개, `ApplicationContextRunner`, 매개변수 포함)** — 정상, 키 없음·공백·Base64 아님·짧음·반복, **두 키 동일**, **표기만 다른 동일 키**,
  정책 설정 오류 4종(음수 오차·0 수명·빈 issuer·빈 audience). 오류 메시지에 키 값 없음
- **`AdmissionTokenFilterTest` (18개, 실제 SecurityFilterChain + 내장 서버 + MySQL)** — 확인용 선점 컨트롤러(테스트 전용, 통과하면 실제 조정자를 부른다)
  - 정상: 통과·principal 유지·입장 정보 전달·실제 선점 / 입장 불필요 API·health 는 요구 안 함
  - 헤더: 없음·두 번·쉼표 결합·빈 값/공백 → 401
  - 토큰: 변조·다른 키(액세스 키)·`alg=none`·HS512·액세스 토큰을 입장 자리에·같은 입장 키로 서명한 액세스 모양·`token_use=access`·
    `iss`/`aud`/`typ` 오류·필수 claim 누락·숫자 `evt`/`sub`·`evt=0`·만료·수명 초과 → 401
  - 대조: 사용자 불일치 → 403(본문에 사용자 id 없음) / **액세스 오류 우선** — 액세스 없음·변조·입장 토큰을 액세스 자리에 → 401 `AUTHENTICATION_REQUIRED`
  - **거부마다 조정자 미호출·멱등키 0행·좌석 AVAILABLE·quota 행 0** 확인
  - 구조: 보안 체인에 1회·서블릿 등록 없음, 메트릭 태그 고정값, Redis 클라이언트 부재

기존 액세스 인증(`AccessTokenAuthenticationTest` 47)·기본 거부·traceId·TX0 데드락 회귀 테스트는 그대로 통과한다.

---

## 4. 전체 검증 기록

2026-11-10, 이전 XML 을 지운 뒤 `./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL in 1m 41s`, 24 tasks executed.
`verifyArchitectureRules` 포함. `git diff --check` 통과.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 392 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 621 | 0 | 0 | 0 |
| `modules:queue-token` | 47 | 0 | 0 | 0 |
| **합계** | **1,263** | **0** | **0** | **0** |

TASK-002J-B §7 의 1,186개에서 +77 (queue-token 47, 앱 30). 기존 테스트를 지우거나 제외하지 않았다.

---

## 5. 의존성

`gradle/libs.versions.toml` 에 `nimbus-jose-jwt` 를 등록했다. **Spring Boot BOM 은 Nimbus 버전을 관리하지 않아**(처음에 버전 없이 등록했다가
해석 실패) 버전 `10.9` 를 카탈로그에 적었다 — Spring Security 7.1(`spring-security-oauth2-jose`)이 가져오는 것과 같은 값이다.
Spring Security 를 올릴 때 함께 맞춰야 한다.

---

## 6. 환경 — 첫 전체 실행의 실패

첫 `check` 에서 Task 2I-A·2I-C 의 **풀 크기 1** 테스트 2개가 `SQLNonTransientConnectionException: Too many connections` 로 실패했다.
코드 결함이 아니라 **테스트 컨텍스트 수** 문제다 — 새 필터 테스트가 2J-B 의 웹 테스트와 다른 구성(`@Import`)이라 Spring 이 내장 서버
컨텍스트를 하나 더 캐시했고, 컨텍스트마다 Hikari 풀(최대 64, 기본 최소 유휴 = 최대)이 연결을 쥐어 MySQL `max_connections`(151)를 넘었다.

운영 풀 설정은 바꾸지 않고, 두 웹 보안 테스트가 같은 구성을 쓰도록 `SecuredWebTestSupport` 로 모아 **컨텍스트 하나를 공유**하게 했다.
재실행에서 연결 오류 0건이다. 테스트 컨텍스트가 더 늘면 같은 문제가 다시 날 수 있다 — 남은 위험으로 둔다.
다른 프로젝트 컨테이너는 이전 작업부터 중지된 상태였고 이번에 바꾸지 않았다.

---

## 7. 남은 위험과 Task 2J-D

- **회차 바인딩 없음** — `evt` 와 요청 좌석의 판매 회차를 아직 대조하지 않는다. 회차 E1 입장 토큰으로 E2 좌석 요청을 막지 못한다
- 토큰 수명 10분·시계 오차 30초는 가정값 (D-A4). 즉시 폐기(blocklist) 없음
- HS256 대칭키 — reservation-service 가 침해되면 입장 토큰을 위조할 수 있다 (ADR-015 §7)
- 웹 테스트 컨텍스트가 늘면 테스트 MySQL 연결 수가 다시 부족해질 수 있다

**Task 2J-D 후속 범위** — 선점 운영 컨트롤러(`POST /api/reservations/holds`), `Idempotency-Key`·본문 검증(§4·§5), 좌석 → 판매 회차 사전 해석과
`evt` 대조(403 `ADMISSION_EVENT_MISMATCH`), D4(명령에 기대 회차 + TX1 안 재대조, 403 비저장), 결과 → HTTP 매핑·`Retry-After`·D1 응답 직렬화,
5xx 분류. 그 뒤 2J-E 간이 발급(D-A1 결정 필요).
