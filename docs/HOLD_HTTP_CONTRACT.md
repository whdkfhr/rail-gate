# RailGate — 좌석 선점 HTTP 계약

> 상태: **설계 계약 v0.1** (2026-10-25, Task 2J-A). **구현 없음** — 컨트롤러·보안 필터·JWT 검증·발급기는 아직 없다.
>
> 관련: [REQUIREMENTS.md](REQUIREMENTS.md) FR-2.1·2.3·2.6·2.9·P-13, [IDEMPOTENCY_CONTRACT.md](IDEMPOTENCY_CONTRACT.md),
> [ADR-009](adr/ADR-009-idempotency-key.md) (C3 채택), [ADR-015](adr/ADR-015-login-and-admission-tokens.md) (인증·입장 토큰),
> [INVARIANTS.md](INVARIANTS.md) I-6·I-7·I-8·I-9·I-12·I-18, [FRONTEND.md](FRONTEND.md), CLAUDE.md 규칙 14~17·21~24,
> 실험 기록 [TASK-002J-A](experiments/TASK-002J-A-hold-http-contract.md)

이 문서의 각 항목은 셋 중 하나로 표시한다.

| 표시 | 뜻 |
|---|---|
| **[확정]** | 이번 Task 에서 정한 계약. 구현은 이것을 따른다 |
| **[제안]** | 권장안. 구현 Task 에서 바꿀 수 있지만 바꾸면 이 문서를 고친다 |
| **[결정 필요]** | 구현 전에 정해야 한다. §8 에 모았다 |

---

## 1. 현재 구현과 이 계약의 경계

| 있음 (코드) | 없음 (이 문서가 설계만 한 것) |
|---|---|
| `IdempotentHoldService.hold(IdempotencyKey, HoldSeatsCommand)` → `IdempotentHoldResult` (Task 2I-C) | HTTP 컨트롤러, 요청·응답 DTO, 오류 매핑 |
| `IdempotencyKey`(ASCII 영숫자·`-`·`_` 8~64자), `HoldSeatsCommand`(1~4석·중복 거부), fingerprint | 로그인 JWT·입장 토큰 검증, 보안 필터, 발급 엔드포인트 |
| 저장 결과 `StoredHoldOutcome` — 성공(`holdId`·`saleEventId`·`seatCount`) / 업무 실패 코드 3종 | traceId 생성·전파, 응답 직렬화, `Retry-After` |
| V7 `idempotency_key`, `JdbcIdempotencyKeyRepository` | 보존 기간 삭제 |

`IdempotentHoldService` 의 사용자 id 는 지금 `HoldSeatsCommand` 에서 온다. 이 계약은 그것을 **인증된 주체에서만** 채우도록
정한다 — 구현 전까지 인증·소유자 검증은 완료가 아니다.

---

## 2. 엔드포인트 [확정]

```http
POST /api/reservations/holds
Authorization: Bearer <로그인 액세스 토큰>
Admission-Token: <입장 토큰>
Idempotency-Key: <클라이언트 키>
Content-Type: application/json

{ "seatIds": [101, 102] }
```

- **경로** — 브라우저가 쓰는 동일 origin 접두사 `/api/reservations`(ARCHITECTURE §2 사용자 웹 경계, FRONTEND §6) 아래 컬렉션
  `holds` 에 POST 한다. 기존 컨트롤러가 없으므로 이것이 reservation-service 의 첫 공개 경로다.
- **[제안]** 서비스는 `/api/reservations/**` 를 **접두사 그대로** 매핑하고, Vite proxy·Nginx 는 경로를 바꾸지 않고 전달한다.
  프록시 유무와 관계없이 같은 경로로 테스트할 수 있다.
- **요청 본문은 `seatIds` 하나만 받는다.** `userId`·`saleEventId` 를 받지 않는다 — 사용자는 인증된 주체에서, 판매 회차는
  서버가 좌석에서 해석한다(I-12 우회 방지, `HoldSeatsCommand` 주석).

---

## 3. 처리 순서와 거부 시 변경 범위 [확정]

| 단계 | 하는 일 | 실패 응답 | 키 | 좌석 | quota |
|---|---|---|---|---|---|
| 1 | 로그인 액세스 토큰 검증 (ADR-015 §3) | 401 `AUTHENTICATION_REQUIRED` | 변경 없음 | 변경 없음 | 변경 없음 |
| 2 | 입장 토큰 검증 — 서명·용도·만료·issuer·audience (ADR-015 §4) | 401 `ADMISSION_TOKEN_INVALID` | 변경 없음 | 변경 없음 | 변경 없음 |
| 3 | 입장 토큰 `sub` = 로그인 `sub` (I-6) | 403 `ADMISSION_USER_MISMATCH` | 변경 없음 | 변경 없음 | 변경 없음 |
| 4 | `Idempotency-Key` 헤더 검증 (§4) | 400 `IDEMPOTENCY_KEY_INVALID` | 변경 없음 | 변경 없음 | 변경 없음 |
| 5 | 본문 파싱·검증 → `HoldSeatsCommand` 생성 (§5) | 400 `INVALID_REQUEST` | 변경 없음 | 변경 없음 | 변경 없음 |
| 6 | 좌석 → 판매 회차 해석 (**읽기 전용**, 트랜잭션 밖) | 422 `SALE_EVENT_SCOPE_REJECTED` | 변경 없음 | 변경 없음 | 변경 없음 |
| 7 | 입장 토큰 `evt` = 해석한 판매 회차 | 403 `ADMISSION_EVENT_MISMATCH` | 변경 없음 | 변경 없음 | 변경 없음 |
| 8 | `IdempotentHoldService.hold` 호출 | §6 매핑 | 조정자 계약대로 | 성공 시에만 | 성공 시에만 |

- **1~7 은 아무것도 쓰지 않는다.** 키를 확보하기 전이므로 같은 키로 고쳐 보낸 요청은 새 요청으로 처리된다.
- **인증을 입력 검증보다 먼저 한다.** 인증되지 않은 요청에 본문 검증 결과를 알려 주지 않는다.
- **6 의 해석은 권한 판정용 사전 조회다.** 선점 트랜잭션은 `HoldSeatsService` 가 같은 해석을 TX1 안에서 다시 한다.
  좌석의 회차 소속은 정상 애플리케이션 경로에서 바뀌지 않으므로(ARCHITECTURE §3-1, TASK-002G-E-B2) 두 결과는 같다고 본다.
  DB 직접 SQL 로 소속을 바꾸면 어긋날 수 있다 — **[결정 필요] D4** 에서 TX1 안 재확인 여부를 정한다.
- 6 에서 없는 좌석·회차 혼합이 드러나면 키를 소비하지 않고 422 를 낸다. 같은 사유가 8 의 TX1 안에서 처음 드러나면(사전 조회와
  TX1 사이에 좌석이 생기거나 사라지는 경우 — 현재 정상 경로에는 없다) 업무 실패로 **저장**되어 재생된다. 코드와 상태는 같다.

---

## 4. `Idempotency-Key` 헤더 [확정]

| 경우 | 처리 |
|---|---|
| 없음 | 400 `IDEMPOTENCY_KEY_INVALID` (규칙 14 — 선택 헤더로 두지 않는다) |
| 형식 위반 — ASCII 영숫자·`-`·`_` 외 문자, 8자 미만·64자 초과, 앞뒤 공백 | 400 `IDEMPOTENCY_KEY_INVALID`. **다듬거나 정규화하지 않는다** (IDEMPOTENCY_CONTRACT §1) |
| **헤더 필드가 둘 이상** (같은 이름이 여러 번) | 400 `IDEMPOTENCY_KEY_INVALID`. 어느 값을 쓸지 서버가 고르지 않는다 |
| 쉼표로 이은 값 (`a,b`) | 쉼표가 허용 문자가 아니므로 형식 위반 400 |
| 대소문자만 다른 키 | **다른 키**다 (바이트 비교) |
| 헤더 이름의 대소문자 | HTTP 규칙대로 구분하지 않는다 (`idempotency-key` 도 같은 헤더) |

키 범위는 (인증된 사용자, `HOLD_SEATS`, 키) 이다. 다른 사용자의 같은 키는 별개다.

---

## 5. 요청 본문 [확정]

```json
{ "seatIds": [101, 102] }
```

| 경우 | 처리 |
|---|---|
| `Content-Type` 이 JSON 이 아님 | 415 `UNSUPPORTED_MEDIA_TYPE` |
| JSON 문법 오류, 본문 없음 | 400 `INVALID_REQUEST` |
| `seatIds` 없음·`null`·배열 아님 | 400 `INVALID_REQUEST` |
| 빈 배열 | 400 `INVALID_REQUEST` |
| 요소가 정수가 아님·1 미만·`long` 범위 밖 | 400 `INVALID_REQUEST` |
| **같은 좌석 중복** | 400 `INVALID_REQUEST` — 키를 소비하지 않는다 (`HoldSeatsCommand` 가 만들어지지 않는다) |
| **5석 이상** | 400 `INVALID_REQUEST` — 1요청 1~4석 (FR-2.3, `SeatCount`). 누적 상한(I-12)과는 다른 검사다 |
| 알려지지 않은 필드 (`userId`, `saleEventId` 포함) | 400 `INVALID_REQUEST`. 무시하지 않는다 — 클라이언트가 그 값이 쓰인다고 오해하지 않게 |

좌석 순서는 결과에 영향이 없다. fingerprint 는 정렬한 좌석 id 로 계산하므로 `[102, 101]` 재요청도 같은 요청이다.

400 본문의 `message` 는 고정 문구다. 어느 필드가 왜 틀렸는지는 **[제안]** `details` 배열에 필드 경로와 규칙 이름만 담는다
(예: `{"field":"seatIds","rule":"MAX_4"}`). 입력값을 그대로 되돌려 주지 않는다.

---

## 6. `IdempotentHoldResult` → HTTP 매핑

### 6.1 표

| 결과 | 업무 결과 | 상태 | `code` | `Retry-After` | 재생 대상 | 분류 |
|---|---|---|---|---|---|---|
| `Executed` | `Succeeded` | **201** | — (성공 본문) | — | 예 | 정상 |
| `Replayed` | `Succeeded` | **201** | — (같은 성공 본문) | — | 예 | 정상 |
| `Executed`·`Replayed` | `Rejected(SEAT_UNAVAILABLE)` | **409** | `SEAT_UNAVAILABLE` | — | 예 | 정상 경합 |
| `Executed`·`Replayed` | `Rejected(QUOTA_EXCEEDED)` | **429** | `QUOTA_EXCEEDED` | **[결정 필요] D3** | 예 | 정상 거절 |
| `Executed`·`Replayed` | `Rejected(SALE_EVENT_SCOPE_REJECTED)` | **422** | `SALE_EVENT_SCOPE_REJECTED` | — | 예 | 클라이언트 오류 |
| `InProgress` | — | **409** | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | `1` (가정) | 아니오 | 정상 |
| `OwnershipLost` | — | **409** | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | `1` (가정) | 아니오 | 정상 |
| `FingerprintMismatch` | — | **422** | `IDEMPOTENCY_KEY_REUSED` | — | 아니오 | 클라이언트 오류 |
| 재시도 가능한 장애 (잠금 대기 초과·데드락·연결 실패) | — | **503** | `SERVICE_UNAVAILABLE` | `1` (가정) | 아니오 | **서버 장애** |
| 그 외 예외 (커밋 결과 불명 포함) | — | **500** | `INTERNAL_ERROR` | — | 아니오 | **서버 장애** |

- **[확정] 실행과 재생은 같은 상태 코드와 같은 업무 결과를 낸다.** 클라이언트는 응답만으로 실행인지 재생인지 구분할 필요가 없다.
- **[확정] ADR-009 C3** — 진행 중과 소유권 상실은 둘 다 `409` + `IDEMPOTENCY_REQUEST_IN_PROGRESS` 다. 소유권을 잃은 시도는
  상태를 남기지 않았고, 같은 키로 다시 보내면 현재 소유자의 결과를 받으므로 클라이언트 입장에서 진행 중과 같다.
  좌석 경합 `409` 는 `code=SEAT_UNAVAILABLE` 로 구분한다. **클라이언트는 상태 코드가 아니라 `code` 로 분기한다.**
- **[확정] `Retry-After` 는 초 단위 정수**(`Retry-After: 1`)다. **재시도 권고 간격이지 완료 예상 시간이 아니다.**
  초기값 1초는 근거 없는 **가정**이다 — 리스(기본 30초, C4 미결정)보다 훨씬 짧게 두어 정상 완료를 빨리 재생하게 하려는 의도다.
- 503 과 500 의 구분은 HTTP 계층이 예외 타입으로 한다(**[제안]** Spring `TransientDataAccessException`·
  `PessimisticLockingFailureException`·`CannotGetJdbcConnectionException` → 503, 나머지 → 500). 둘 다 조정자가 결과를 저장하지
  않았고 리스 포기를 시도했으므로 같은 키 재시도가 다시 실행될 수 있다.
- 커밋 결과 불명은 500 이다. 실제로 커밋됐다면 같은 키 재시도가 저장된 성공을 재생한다.

### 6.2 로그·오류율 분류 [확정]

| 분류 | 응답 | 로그 수준 | 서버 오류율 |
|---|---|---|---|
| 정상 | 201, 409 `SEAT_UNAVAILABLE`, 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`, 429 `QUOTA_EXCEEDED` | INFO 이하 | **포함하지 않는다** (규칙 21·22) |
| 클라이언트 오류 | 400, 401, 403, 415, 422 | INFO (인증 실패 급증은 별도 메트릭으로 관찰) | 포함하지 않는다 |
| 서버 장애 | 500, 503 | **ERROR** | **포함한다** |

409 를 상태 코드만으로 묶어 세지 않는다. `code` 태그로 나눠 센다 — 같은 키 진행 중이 늘면 리스·재시도 간격 문제이고,
좌석 경합이 늘면 인기 좌석 문제다.

---

## 7. 응답 본문

### 7.1 성공 (201) [확정 — 단 D2]

```http
HTTP/1.1 201 Created
Location: /api/reservations/holds/7f3c2a1e-0b4d-4c55-9a8e-2f6d1c0b9e11
Content-Type: application/json

{ "holdId": "7f3c2a1e-0b4d-4c55-9a8e-2f6d1c0b9e11", "saleEventId": 9000000, "seatCount": 2 }
```

본문의 세 필드는 **저장된 성공 결과에서만** 만든다. 그래서 재생 본문이 실행 본문과 같다. `Location` 도 `holdId` 에서 정해진다.

- **재생은 현재 상태가 아니다.** 재생한 `holdId` 는 이미 해제·만료됐을 수 있다. 현재 상태와 만료 시각은 홀드 조회(후속 API)로 본다.
- `expiresAt` 은 넣지 않는다. 저장 결과에 없고, 재생 시점에 다시 읽으면 실행 응답과 달라진다 — **[결정 필요] D2**.

### 7.2 오류 [D1 결정 전 잠정]

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
Retry-After: 1

{ "code": "IDEMPOTENCY_REQUEST_IN_PROGRESS", "message": "같은 요청을 처리 중입니다. 잠시 후 같은 키로 다시 시도하세요.", "traceId": "…" }
```

- 규칙 24 의 `{ code, message, traceId }` 형식이다. **`message` 는 `code` 마다 고정된 문구**이며 예외 메시지·SQL·스택·
  내부 클래스 이름·사용자 id·멱등키·좌석 상태 상세를 담지 않는다.
- 재생 대상 업무 실패(409 `SEAT_UNAVAILABLE`, 429, 422 `SALE_EVENT_SCOPE_REJECTED`)의 `traceId` 를 어떻게 다룰지는
  **§7.3 / D1** 이 정한다.

### 7.3 ★ 최초 응답 재생과 traceId

세 요구가 부딪힌다.

| 요구 | 내용 |
|---|---|
| 규칙 17 | 재요청에는 **저장된 최초 응답을 그대로** 반환한다 |
| 규칙 24 | 오류 본문은 `{ code, message, traceId }` |
| I-18 검증 방법(T-4) | 응답 유실 뒤 같은 키 재요청 → **두 응답이 바이트 단위로 동일** |

**현재 저장 모델에는 `code` 와 성공 필드만 있다** (V7 `result_type`·`hold_id`·`sale_event_id`·`seat_count`).
오류 응답의 `message`·`traceId`, 상태 코드, 헤더는 저장되지 않는다. 그래서

- `message` 는 `code` 의 고정 문구로 만들면 재생해도 같다 — 저장하지 않아도 된다.
- **`traceId` 는 요청마다 다르다.** 재생 본문에 재요청의 `traceId` 를 넣으면 업무 결과는 같아도 **바이트 동일은 아니다.**
  "같은 응답" 이라고 단정하지 않는다.
- 최초 요청의 `traceId` 를 재생하려면 저장해야 하는데, 그러면 재요청 본문에 **다른 요청의** 추적 id 가 들어가 관측이 꼬인다.

해결안:

| 안 | 내용 | 저장·migration | 장점 | 단점 |
|---|---|---|---|---|
| **A** | 최초 응답(상태·본문 바이트, 원 `traceId` 포함)을 키 행에 저장하고 재생 시 그대로 낸다. 요청별 `traceId` 는 헤더로 | `response_status`·`response_body` 열 추가 (V8) | 규칙 17·24·T-4 문구를 그대로 만족 | 표현 형식이 저장소에 굳는다(DTO 를 바꾸면 옛 행과 달라짐). 성공 본문을 TX1 안에서 직렬화해야 한다. 재생 본문의 `traceId` 가 현재 요청 것이 아니다 |
| **B (권장)** | **재생 범위를 "상태 코드 + 본문" 으로, 본문은 저장 결과에서 결정적으로 만든다.** 재생 대상 응답 본문에는 `traceId` 를 넣지 않고, **모든 응답이 요청별 `traceId` 를 헤더(`X-Trace-Id`)로 낸다.** 재생 대상이 아닌 오류(400·401·403·5xx 등)는 본문에도 `traceId` 를 둔다 | 없음 | migration 없음. 재생 본문이 바이트 단위로 같다. 추적 id 는 언제나 현재 요청 것 | **규칙 24 의 예외**가 생긴다 — 재생 대상 업무 실패 본문에 `traceId` 가 없다 |
| C | 본문에 요청별 `traceId` 를 넣고 I-18 의 "바이트 동일" 을 "업무 결과 동일" 로 완화 | 없음 | 단순 | I-18 검증 기준 완화 |

권장은 **B** 다. 다만 B 와 C 는 기존 규칙(24)이나 불변식 검증 기준(I-18)을 바꾸는 일이므로 **임의로 개정하지 않고
[결정 필요] D1** 로 남긴다. 결정 전까지 구현 Task 는 선점 HTTP 연결을 시작하지 않는다(§9).

재생 범위 정의 (B 기준):

| 구성 | 재생 대상 | 비고 |
|---|---|---|
| 상태 코드 | 예 | §6.1 |
| 본문 | 예 | 저장 결과 + 고정 문구로 결정적으로 생성 |
| `Location` | 예 | `holdId` 에서 생성 |
| `Content-Type` | 예 | 고정 |
| `X-Trace-Id` | **아니오** — 요청별 관측 정보 | 현재 요청의 id |
| `Date`·서버 헤더 | 아니오 | HTTP 기본 |
| **[제안]** `Idempotent-Replayed: true` | 아니오 — 요청별 관측 정보 | 클라이언트 분기용이 아니라 디버깅용. 응답 의미를 바꾸지 않는다 |

---

## 8. 결정이 필요한 항목

| # | 항목 | 권장안 | 막는 작업 |
|---|---|---|---|
| **D1** | 재생 응답과 traceId (§7.3) | **B** — 재생 범위를 상태+본문으로, traceId 는 `X-Trace-Id` 헤더, 재생 대상 업무 실패 본문은 규칙 24 의 예외 | 선점 HTTP 연결 |
| **D2** | 성공 본문의 `expiresAt` | 넣지 않는다. 홀드 조회 API 가 현재 만료 시각을 준다 (넣으려면 저장 결과·V8 필요) | 선점 HTTP 연결, 프론트 카운트다운 |
| **D3** | 429 `QUOTA_EXCEEDED` 의 `Retry-After` | 규칙 22 는 429 에 `Retry-After` 를 요구하지만 quota 는 시간이 아니라 해제·만료로 풀린다. 상태를 **409 `QUOTA_EXCEEDED`** 로 옮기거나, 429 를 유지하고 `Retry-After` 를 "재시도 권고 간격(가정값)" 으로 둔다. I-12 문서가 429 를 쓰고 있어 **임의로 바꾸지 않는다** | 선점 HTTP 연결 |
| **D4** | 입장 회차 검사의 위치 | 사전 조회(§3 단계 6~7)에 더해, 명령에 기대 회차를 실어 `HoldSeatsService` 가 TX1 안에서 해석한 회차와 대조 — 어긋나면 업무 실패가 아닌 403(저장하지 않음) | 선점 HTTP 연결 (보강은 후속 가능) |
| C4 | 리스·보존 수치 (ADR-009) | 미결정 유지 | — |
| ADR-015 D-A1 | 간이 JWT 발급의 신원 확인 방법 | ADR-015 §6 | 발급 엔드포인트 |

---

## 9. 테스트 시나리오 (구현 Task 의 인수 기준)

### 인증·권한

| # | 시나리오 | 기대 |
|---|---|---|
| A1 | 액세스 토큰 없음 / 서명 틀림 / 만료 / `alg=none` / 다른 issuer·audience / 입장 토큰을 액세스 토큰 자리에 | 401 `AUTHENTICATION_REQUIRED`, 키·좌석·quota 불변 |
| A2 | 입장 토큰 없음 / 만료 / 서명 틀림 / 액세스 토큰을 입장 토큰 자리에 | 401 `ADMISSION_TOKEN_INVALID`, 불변 |
| A3 | 사용자 A 의 액세스 토큰 + 사용자 B 의 입장 토큰 (I-6) | 403 `ADMISSION_USER_MISMATCH`, 불변 |
| A4 | 회차 E1 입장 토큰으로 회차 E2 좌석 요청 | 403 `ADMISSION_EVENT_MISMATCH`, 키 행 없음 |
| A5 | 본문에 `userId` 를 넣어 남의 키 범위를 노림 | 400 `INVALID_REQUEST` (알려지지 않은 필드) |
| A6 | 입장 검증 중 Redis·queue-service 중단 | 서명 검증만으로 판정 — 네트워크 호출 0 (I-7, 규칙 4) |

### 입력

| # | 시나리오 | 기대 |
|---|---|---|
| V1 | 키 없음 / 7자 / 65자 / 공백 포함 / 한글 / 헤더 2개 / `a,b` | 400 `IDEMPOTENCY_KEY_INVALID`, 키 행 없음 |
| V2 | 잘못된 JSON / 빈 배열 / 중복 좌석 / 5석 / 0·음수 / 문자열 id | 400 `INVALID_REQUEST`, 키 행 없음 |
| V3 | 없는 좌석 / 회차 혼합 | 422 `SALE_EVENT_SCOPE_REJECTED`, 키 행 없음 (사전 조회 단계) |
| V4 | 오류 본문에 예외 메시지·SQL·사용자 id·키가 없는지 | 고정 문구만 |

### 멱등성

| # | 시나리오 | 기대 |
|---|---|---|
| I1 | 같은 키·같은 좌석 두 번 / 순서만 다름 | 둘 다 201, 본문 동일(D1 기준), 선점 1회 |
| I2 | 같은 키·다른 좌석 | 422 `IDEMPOTENCY_KEY_REUSED`, 실행 없음 |
| I3 | 다른 사용자·같은 키 | 별개 실행 |
| I4 | 경합 409 `SEAT_UNAVAILABLE` 뒤 좌석이 비고 같은 키 재요청 | 409 재생 (재실행 없음) |
| I5 | 같은 키 동시 요청 N 개 | 201 하나 이상(실행 1 + 재생), 나머지 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` + `Retry-After`, 5xx 0 |
| I6 | 성공 뒤 홀드 해제, 같은 키 재요청 | 201 재생, 재선점 없음 |

### 장애

| # | 시나리오 | 기대 |
|---|---|---|
| F1 | TX1 중 잠금 대기 초과 주입 | 503 + `Retry-After`, ERROR 로그, 좌석·quota 롤백, 즉시 재시도는 인수해 실행 |
| F2 | 커밋 결과 불명 주입 | 500, 재시도 시 저장된 성공 재생 |
| F3 | 업무 409·429 대량 발생 | 서버 오류율 메트릭 불변, ERROR 로그 0 |

---

## 10. 다음 구현 순서

[TASK-002J-A](experiments/TASK-002J-A-hold-http-contract.md) §5 에 작은 단위로 나눴다. 요약: **D1~D3 결정 → 인증 기반(액세스 토큰 검증) →
입장 토큰 검증 → 선점 HTTP 연결 → 간이 발급 엔드포인트.**
