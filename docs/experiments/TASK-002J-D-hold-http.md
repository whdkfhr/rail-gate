# TASK-002J-D — 선점 HTTP 연결과 D4 (TX1 회차 재대조)

> 날짜: 2026-11-17. 브랜치 `codex/task-002j-d-hold-http`.
> 관련: [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md) §2~§9, [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md) §5,
> [ADR-009](../adr/ADR-009-idempotency-key.md), [ADR-015](../adr/ADR-015-login-and-admission-tokens.md), INVARIANTS I-6·I-7·I-12·I-18

---

## 1. 범위

| 한 것 | 하지 않은 것 |
|---|---|
| 운영 `POST /api/reservations/holds` (`HoldController`) — §3 처리 순서 4~8 | 토큰 발급기(D-A1), 대기열, 프론트 |
| `Idempotency-Key`·`Content-Type`·본문 검증 (`HoldRequestParser`) | 새 migration, 인증 설정 보강 |
| 읽기 전용 회차 사전 해석·입장 회차 대조 (403 `ADMISSION_EVENT_MISMATCH`) | 홀드 조회·해제·결제 등 **다른 좌석 HTTP API** (없다) |
| D4 — `HoldSeatsCommand.expectedSaleEventId`, TX1 안 재대조, 조정자의 결과 미저장·리스 포기 | D-A1·D-A4·C4 결정 (미결정 그대로) |
| 결과 → HTTP 매핑·응답 직렬화 (`HoldHttpResponses`, `HoldErrorCode`), 응답 메트릭 | 400 의 `details` 배열 ([제안], 넣지 않았다) |
| 테스트 전용 `HoldProbeController` 제거 — 같은 경로를 운영 컨트롤러가 쓴다 | |

## 2. 설계

### 처리 순서 (계약 §3 그대로)

액세스 인증(필터) → 입장 검증·사용자 대조(필터) → `Idempotency-Key` → `Content-Type`(415)·본문(400) → **읽기 전용 회차 해석**(기존
`SaleEventScopePort.resolve`, 트랜잭션 밖) → **입장 회차 대조** → 조정자. 1~7 단계는 아무것도 쓰지 않는다. 재생 요청도 7 단계를 지난다 —
다른 회차의 입장 토큰으로 저장된 결과를 받을 수 없다. 조정자를 바깥 트랜잭션으로 감싸지 않는다(조정자가 바깥 트랜잭션을 거부한다).

- 사용자는 `AuthenticatedUser` 에서만, 기대 회차는 `AdmissionContext` 에서만 온다. 입장 정보가 없으면(필터 배선 오류) 선점하지 않고 500.
- 키: 같은 이름의 헤더 필드를 모두 모아 **정확히 하나**일 때만, `IdempotencyKey` 형식(ASCII 영숫자·`-`·`_` 8~64자)으로 받는다. 다듬지 않는다.
  다만 HTTP 필드 값 앞뒤의 공백(OWS)은 규격상 값이 아니라 서버(Tomcat)가 넘기기 전에 걷어낼 수 있다 — 앞뒤 공백 거부는 파서 단위 테스트로만 확인했고 HTTP 로는 확인하지 않았다.
- 본문: Jackson 트리로 읽고 직접 검사한다(라이브러리의 문자열·소수 → 정수 변환을 쓰지 않으려고). 같은 이름 필드 반복·뒤따르는 토큰도 거부한다.
  **본문 상한 4,096 바이트는 [제안] 수치**다 — 넘으면 파싱하지 않고 400.
- `Content-Type` 은 `application/json`(매개변수 무시)만 받는다. 와일드카드·`+json`·누락은 415.

### 응답

- 201 본문·`Location` 은 **저장된 성공 결과**에서만 만든다. 좌석을 다시 읽지 않고 `expiresAt` 을 넣지 않는다(D2).
- JSON 은 직접 쓴다 — 값이 고정 문구·UUID·양의 정수뿐이고, 직렬화 설정 변경이 재생 바이트를 바꾸지 않게 하려는 것이다(§7.3 배포 간 호환).
  `Content-Type: application/json;charset=UTF-8` (보안 단계 오류와 같다).
- 재생 대상(201, 409 `SEAT_UNAVAILABLE`·`QUOTA_EXCEEDED`, 422 `SALE_EVENT_SCOPE_REJECTED`) 본문에는 `traceId` 가 없다. 그 밖의 오류는 본문에 현재 traceId.
- ~~사전 조회의 422 는 저장된 422 와 같은 바이트로 냈다~~ → **PR #37 리뷰로 정정 (§6)**: 사전 조회 422 는 현재 요청의 거절이라 본문에 traceId 를 담는다.
- 503: `TransientDataAccessException`(잠금 대기 초과·데드락·질의 시간 초과)·`PessimisticLockingFailureException`·`CannotGetJdbcConnectionException`·
  `CannotCreateTransactionException`(트랜잭션 시작 시 연결 실패). 그 밖(커밋 결과 불명 포함)은 500. 둘 다 ERROR 로그.

### D4 — TX1 회차 재대조

- `HoldSeatsCommand(userId, seatIds, expectedSaleEventId)` — 기대 회차는 **필수**다. 입장 권한 없이 선점하는 경로를 남기지 않으려고
  2인자 생성자를 두지 않았다. fingerprint(`hold-seats/v1`)는 바꾸지 않았다.
- `HoldSeatsService` 는 회차 해석 직후, quota·좌석 변경 전에 대조하고 `AdmissionEventMismatchException` 을 던진다(TX1 롤백). 잠금 순서
  quota → 좌석 → 키는 그대로다 — 대조는 잠금을 잡기 전이다.
- 조정자는 이 예외를 업무 실패로 저장하지 않고 기존 `abandon`(세대·fence·`IN_PROGRESS` 조건)으로 리스를 포기한 뒤 전파한다.
  메트릭은 `calls{result=admission_event_mismatch}` — `failed` 에 섞지 않는다. HTTP 계층은 403 으로 바꾸고 서버 오류로 세지 않는다.
- 결과: 사전 거절은 **키 행 없음**, TX1 거절은 **결과 없는 `IN_PROGRESS` + 만료된 리스**. 같은 키·같은 요청은 인수해 다시 대조하고,
  다른 좌석이면 fingerprint 불일치 422 다.
- TX0 데드락 재시도 범위(TX0 의 INSERT·인수만, 3회)와 TX0/TX1/TX2 경계는 바꾸지 않았다.

### 관측

`railgate.hold.http.responses{status, code, category}` — `code` 는 `HoldErrorCode` 이름 또는 `none`, `category` 는
`normal`·`client_error`·`server_error`(§6.2). 409(정상)와 4xx 는 `server_error` 가 아니다. 로그: 5xx ERROR, 클라이언트 오류 INFO,
정상 409 DEBUG. 사용자 id·키·좌석 id·토큰·본문은 태그와 로그에 없다.

## 3. ★ TDD 기록

| 단계 | 테스트 | RED | GREEN |
|---|---|---|---|
| 1 요청 검증·응답 매핑 | `HoldHttpContractTest` (신규 72) | 골격(`UnsupportedOperationException`)에서 **72/72 실패** | 72/72 |
| 2 D4 | `HoldSeatsServiceTest`(+2)·`IdempotentHoldServiceTest`(+3)·`IdempotentHoldServiceIntegrationTest`(+3) | 회차 대조 없는 서비스에서 **79개 중 5개 실패** — 서비스 단위 1, 조정자 지표 1, MySQL 3 | 94/94 (기존 `HoldSeatsServiceIntegrationTest` 포함) |
| 3 HTTP·장애 | `HoldControllerIntegrationTest`(신규 33)·`HoldControllerFaultTest`(신규 2, 리뷰 보완 뒤 3)·`AdmissionTokenFilterTest` | 골격 컨트롤러에서 **56개 중 33개 실패** | 128/128 (단계 1 포함 묶음) |

- 단계 2 의 조정자 단위 테스트 중 "결과를 저장하지 않고 리스를 포기한다" 는 **수정 전에도 통과**했다 — 기존 장애 경로가 이미 그렇게 처리했다.
  RED 는 지표 분리(`failed` → `admission_event_mismatch`)였다. 통과한 테스트를 D4 방어의 RED 로 세지 않았다.
- 단계 3 에서 통과한 23개는 필터 단계(401·403 사용자 불일치)와 기존 입장 필터 테스트다 — 컨트롤러 이전에 결정되는 계약이다.
- 기존 테스트의 입력 조정: 명령에 기대 회차가 필수가 되어 테스트 61곳에 기대 회차를 넣었다. 다른 회차 좌석을 쓰는 곳은 좌석의 회차를 기대 회차로 쓰게 고쳤다 —
  `HoldSeatsServiceIntegrationTest` 2곳, `HoldSeatsServiceTest` 1곳, 확정·만료·해제 통합 테스트의 선점 픽스처 3곳(`MySqlSpringTestSupport.saleEventOf`). 어서션은 바꾸지 않았다.
- `AdmissionTokenFilterTest` 는 테스트 전용 확인용 컨트롤러 대신 운영 컨트롤러를 부른다. "조정자 미호출" 근거를 호출 수 카운터에서
  조정자 메트릭 `railgate.idempotency.hold.calls` 의 합으로 바꿨고, 정상 경로 단언은 200 → 201·선점 주체 = 로그인 사용자로 바꿨다.
- 새 동시성 방어는 없다 — 같은 키 경합(I5)은 기존 조정자의 방어를 HTTP 로 다시 확인한 것이다(실패 기록은 TASK-002I-C).

### 계약 §9 시나리오와 테스트

| # | 테스트 | 결과 |
|---|---|---|
| A1~A3 | 기존 `AccessTokenAuthenticationTest`·`AdmissionTokenFilterTest`(운영 컨트롤러로 전환), `HoldControllerIntegrationTest` §3 | 401/403, 조정자 미호출·키·좌석·quota 불변 |
| A4 | §3 다른 회차 입장 토큰 / 다른 회차 토큰으로 저장된 성공 재생 시도 | 403 `ADMISSION_EVENT_MISMATCH`, 키 행 없음, 재생 거부 후 올바른 토큰은 같은 바이트 재생 |
| A5 | §3 본문 `userId`·`saleEventId` | 400 |
| A6 | 기존 `AdmissionTokenFilterTest` (Redis 클라이언트 없음) | — |
| T1~T3 | 모든 응답 `X-Trace-Id` 하나, 비재생 오류 본문 traceId = 헤더, 재생 바이트·Location·Content-Type 동일·traceId 다름 | 통과 |
| V1~V4 | §4 | 400·415·422, 키 행 없음, 입력값·키·사용자 id 미노출 |
| I1~I6 | §1·§2·§5 | 재생 바이트 동일, 422 재사용, 사용자 격리, 409 재생, 동시 8요청 선점 1회·5xx 0, 해제 뒤 재생 |
| F1 | §5 좌석 행 `FOR UPDATE` 로 TX1 을 잠금 대기 초과(3초)시킴 | 503 + `Retry-After: 1`, 좌석·quota 롤백, 즉시 재시도 201(인수) |
| F2 | `HoldControllerFaultTest` — 두 번째 COMMIT(TX1)을 실행한 뒤 실패 보고 | 500, 행은 `COMPLETED`, 재요청 201 재생·선점 1회 |
| F3 | §2 409 뒤 `server_error` 합 불변 | 통과 |
| F4 | §2 상한 초과 재생, 새 키 실행 | 통과 |
| F5 | `HoldControllerFaultTest` — 사전 조회 직후 좌석을 다른 회차 운행편으로 옮김 | 403, 결과 미저장, 좌석·quota 불변, `failed` 불변, 같은 키 인수 후 201 |

F2·F5 는 HTTP 요청만으로 주입할 수 없어 **필터 체인을 거치지 않고** 운영 컨트롤러를 실제 MySQL 빈과 함께 직접 불렀다. 실제 COMMIT 중 연결 단절은
재현하지 않았다. ERROR 로그 건수는 어서션하지 않았다(메트릭 `category` 로 확인).

### 3-1. PR 전 리뷰(`/code-review` medium) 보완

| 지적 | 처리 |
|---|---|
| 입장 필터가 원문 URI 로 경로를 비교해 `POST /api/reservations/%68olds` 가 필터를 건너뛰고 컨트롤러에 닿음 (컨트롤러가 500 으로 막았다) | **RED 먼저** — `AdmissionTokenFilterTest` §2-1 이 500 으로 실패. 필터를 MVC 와 같은 `PathPatternRequestMatcher` 판정으로 바꿔 401. 인증 설정을 넓히거나 바꾼 것이 아니라 같은 경로의 판정 기준을 맞춘 수정이다 |
| 본문 읽기의 `IOException` 이 컨트롤러 매핑·메트릭을 건너뜀 | 400 `INVALID_REQUEST`(클라이언트 중단)로 매핑. `HoldControllerFaultTest` 에 회귀 테스트 — **수정 뒤에 작성해 RED 기록은 없다** |
| 규칙 33 — `userId`·`holdId` 가 MDC 에 없음 | 요청 동안 `userId`, 성공·재생 시 `holdId` 를 넣고 끝나면 지운다 (MDC 비움을 테스트) |
| `Content-Type` 상수가 운영 코드에서 쓰이지 않음 | `HoldHttpResponse.CONTENT_TYPE` 하나로 설정 |
| 필터 테스트의 "미진입" 근거가 조정자 호출 수뿐이라 컨트롤러 진입을 놓칠 수 있음 | 컨트롤러 응답 메트릭 합을 함께 본다. GET 은 405 를 정확히 단언 |
| 재생보다 사전 회차 조회가 먼저라 좌석 소속이 바뀌면 저장된 결과를 재생하지 못함 (규칙 17) | **계약 §3·§8.1 이 정한 순서**(재생도 사전 대조)라 바꾸지 않았다. 남은 위험에 적는다 |
| 커밋 결과 불명이 `TransientDataAccessException` 하위로 번역되면 500 이 아니라 503 | 고치지 않았다 — 예외 타입만으로 커밋 단계 실패를 구분할 수 없다. 같은 키 재시도는 어느 쪽이든 안전하다(완료 행 재생). 남은 위험 |
| 회차 해석 2회(사전·TX1) | 측정하지 않았다. 남은 위험 |

## 4. 전체 검증

2026-11-17, `docker info`(27.3.1) 확인.

**첫 `./gradlew check --rerun-tasks --max-workers=1` — 실패 3건, 앱 테스트에서 중단** (나머지 모듈 미실행):

- `ConfirmSeatServiceIntegrationTest`·`ExpireHoldsServiceIntegrationTest` 각 1 — 다른 회차 좌석을 고정 기대 회차로 선점하는 픽스처.
  **D4 가 의도대로 거부한 것**이라 픽스처가 좌석의 회차를 쓰게 고쳤다(위 §3).
- `HoldIdempotencyFailureWindowTest` §4 같은 키 동시 요청 1 — **Task 2I-A 실험용 저장소**(`task2ia_idempotency_key`, 데드락 재시도 없음)의 INSERT 가
  MySQL 데드락(1213)으로 실패. PR #32 와 같은 메커니즘이고 이번 변경이 닿지 않는 실험 코드다. 그 테스트만 8회 재실행해 **0/8 실패**, 아래 두 번째 전체
  실행에서도 통과했다. 간헐 실패로 남은 위험에 적는다 — 재시도·제외로 숨기지 않았다.

**두 번째 실행**(리뷰 보완 전) — `BUILD SUCCESSFUL in 1m 33s`, 1,420개 실패 0.

**세 번째 실행**(리뷰 보완 후, 최종) — `BUILD SUCCESSFUL in 1m 48s`, 24 tasks executed, `verifyArchitectureRules` 포함.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 536 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 621 | 0 | 0 | 0 |
| `modules:queue-token` | 62 | 0 | 0 | 0 |
| **합계** | **1,422** | **0** | **0** | **0** |

TASK-002J-B §9 의 1,305개에서 +117 (계약 72, HTTP 통합 33, 장애 경계 3, D4 단위·조정자·MySQL 8, 인코딩 경로 1). 기존 테스트를 지우거나 제외하지 않았다 — 테스트 전용 컨트롤러 파일만 지웠다.

## 5. 남은 위험

- **I-7 은 선점 경로에서만** 강제된다. 홀드 조회·해제·결제 등 다른 좌석 HTTP API 는 아직 없다 — 생기면 같은 입장 검증을 붙여야 한다
- 토큰 발급기가 없다(D-A1). 운영에서 입장 토큰을 받을 길이 없으므로 이 API 는 아직 실제 사용자가 쓸 수 없다
- 리스 30초·`Retry-After` 1초·본문 상한 4,096 바이트·토큰 수명·시계 오차는 측정 근거 없는 가정값이다 (C4·D-A4 미결정)
- `HoldIdempotencyFailureWindowTest` 의 실험 저장소 동시 INSERT 데드락 간헐 실패 (위 §4)
- 사전 조회와 TX1 이 같은 회차 조회를 두 번 한다 — 요청당 조회 1회 추가 비용은 측정하지 않았다
- 보존 기간 삭제가 없어 배포 간 응답 호환 조건(§7.3)이 무기한 걸린다
- 재생도 사전 회차 대조를 거치므로, 좌석이 삭제·이동(정상 경로에는 없음)되면 같은 키가 저장된 결과 대신 422·403 을 받는다 — 계약이 정한 교환이다
- 커밋 결과 불명이 일시 장애 예외로 번역되면 500 이 아니라 503 으로 분류될 수 있다 (재시도 안전성은 같다)

---

## 6. PR #37 리뷰 보완 (2026-11-17)

### 6.1 multipart 선처리 제거

- **사용처 확인** — `apps`·`modules` 의 운영·테스트 소스에 `MultipartFile`·`@RequestPart`·multipart 설정이 없다(`grep`). 테스트의 `multipart/form-data`
  Content-Type 문자열(415 대상) 하나뿐이다.
- **원인** — Spring Boot 기본값으로 multipart 가 켜져 있어 DispatcherServlet 이 컨트롤러 전에 multipart 본문을 파싱한다.
- **수정** — `application.yml` 에 `spring.servlet.multipart.enabled: false`. 선점 경로만 보호하는 대안은 필요 없었다(다른 사용처 없음).
- **RED** — `HoldControllerIntegrationTest` §4-1 의 큰 파트(2MB) multipart 요청이 컨트롤러 전에 **413** 으로 끝났다(기대 415). 같은 묶음의 나머지
  4개(인증 없음 401, 키 없음 400, 정상·깨진 multipart 415, 정상 JSON·본문 상한)는 **수정 전에도 통과**했다 — 작은 본문은 파싱돼도 컨트롤러가 같은 응답을 냈다.
- **GREEN** — 5/5. 415 는 고정 본문(리터럴 비교)·본문 traceId = `X-Trace-Id`, 조정자 미호출·멱등키·좌석·quota 불변을 확인한다.

### 6.2 사전 조회 422 와 저장된 422 분리

- **수정** — `HoldHttpResponses.error` 는 코드와 무관하게 **현재 요청의 거절**(`{code, message, traceId}`)만 만든다. 저장된 업무 실패는 별도
  `storedRejection` 이 `{code, message}` 로 만든다. `HoldErrorCode.replayable` 은 형식 선택에 더는 쓰지 않는다(저장될 수 있는 코드 표시만).
- **RED** — 계약 단위 테스트 1개(사전 조회 422 본문에 traceId)와 HTTP 1개(없는 좌석·회차 혼합 422 의 본문 traceId = 헤더)가 실패.
- **수정 전에도 통과한 회귀 테스트** — 저장된 409·422 본문의 **고정 리터럴 바이트**(구현 상수를 읽지 않는다), TX1 에서 처음 드러나 저장된 422 의
  실행·재생 바이트 동일(`HoldControllerFaultTest`, 사전 조회 직후 좌석 하나를 다른 회차로 옮겨 TX1 이 혼합을 처음 보게 함 — 필터 체인 없이 직접 호출),
  기존 201·409 실행·재생 HTTP 테스트. 저장 경로는 바꾸지 않았으므로 RED 로 세지 않았다.
- 규칙 24·D1 은 바꾸지 않았다 — 이전 판단(사전 조회 422 를 재생 형식으로)이 규칙 24 예외를 넓힌 것이었고, 그것을 되돌렸다.

### 6.3 전체 검증

2026-11-17, `docker info`(27.3.1) 확인 후 좁힌 테스트(인증·입장·선점 HTTP·계약·장애 207개, 실패 0), 이어서
`./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL in 1m 43s`, 24 tasks, `verifyArchitectureRules` 포함.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 543 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 621 | 0 | 0 | 0 |
| `modules:queue-token` | 62 | 0 | 0 | 0 |
| **합계** | **1,429** | **0** | **0** | **0** |

§4 의 1,422개에서 +7 (multipart 5, 저장된 422 고정 바이트 1, TX1 저장 422 실행·재생 1 — 사전 조회 422 계약 테스트는 이름·기대값을 바꿨다).
