# TASK-002J-A — 선점 HTTP API·인증 경계 설계

> 관련: [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md), [ADR-015](../adr/ADR-015-login-and-admission-tokens.md),
> [ADR-009](../adr/ADR-009-idempotency-key.md) (C3 채택), [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md),
> [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.1·2.3·2.6·2.9·P-13, [INVARIANTS.md](../INVARIANTS.md) I-6·I-7·I-18,
> [TASK-002I-C](TASK-002I-C-idempotent-hold-service.md) — 서비스 수준 멱등 선점 조정자

## 1. 목적과 범위

`IdempotentHoldService`(Task 2I-C) 앞에 놓일 **선점 HTTP API 와 인증·입장 권한 경계를 설계하고 계약 문서로 남긴다.**
이번 Task 는 **문서만** 바꾼다 — 컨트롤러·JWT 검증·발급기·보안 설정·migration·새 의존성은 추가하지 않았다.

**산출물**

- [HOLD_HTTP_CONTRACT.md](../HOLD_HTTP_CONTRACT.md) — 경로, 처리 순서와 단계별 무변경 보장, 헤더·본문 검증, 결과 → HTTP 매핑,
  로그·오류율 분류, 응답 예시, 재생과 traceId 검토, 결정 항목, 테스트 시나리오
- [ADR-015](../adr/ADR-015-login-and-admission-tokens.md) — 로그인 액세스 토큰과 입장 토큰의 분리 (제안)
- ADR-009 **C3 채택**, CLAUDE.md 규칙 21 을 `code` 별 의미로 확장
- IDEMPOTENCY_CONTRACT §7·FRONTEND §4·ARCHITECTURE §9·README·INVARIANTS I-18 의 상태 정합, 조정자 Javadoc 두 곳의 C3 상태 문구

---

## 2. 현재 코드에서 확인한 것

| 확인 | 결과 |
|---|---|
| reservation-service 의 HTTP 경로 | **없다.** `@RestController`·`@RequestMapping` 0개. 경로 규약은 ARCHITECTURE·FRONTEND 의 동일 origin 접두사 `/api/reservations` 뿐 |
| 인증·JWT | **없다.** Spring Security·JWT 라이브러리 의존성 없음, `queue-token` 모듈에 소스 없음 |
| traceId | **없다.** MDC·필터·응답 헤더 없음 |
| 조정자 입력 | `IdempotencyKey`(ASCII 영숫자·`-`·`_` 8~64자, 바이트 비교) + `HoldSeatsCommand(UserId, List<SeatId>)` — 1~4석(`SeatCount`), 중복 거부, `SeatId` 양수 |
| 조정자 결과 | `Executed`·`Replayed`(같은 `StoredHoldOutcome`)·`InProgress`·`FingerprintMismatch`·`OwnershipLost`, 장애는 예외 |
| 저장 결과 | `Succeeded(holdId, saleEventId, seatCount)` / `Rejected(SEAT_UNAVAILABLE·QUOTA_EXCEEDED·SALE_EVENT_SCOPE_REJECTED)` — **`message`·`traceId`·`expiresAt`·상태 코드는 저장하지 않는다** |
| 사용자 id 의 출처 | `HoldSeatsCommand.userId()` — 인증 주체와 대조하지 않는다 |

---

## 3. 주요 결정

### 이번에 확정한 것

| 항목 | 결정 | 근거 |
|---|---|---|
| 경로 | `POST /api/reservations/holds` | 기존 프록시 접두사 아래 컬렉션 생성 |
| 본문 | `{ "seatIds": [...] }` 만. 알려지지 않은 필드(`userId` 포함)는 400 | 사용자는 인증 주체에서, 회차는 서버 해석 |
| 처리 순서 | 액세스 토큰 → 입장 토큰 → 사용자 대조 → 키 헤더 → 본문 → 회차 해석(읽기 전용) → 입장 회차 대조 → 조정자 | 1~7 단계는 키·좌석·quota 를 바꾸지 않는다 |
| 헤더 | `Idempotency-Key` 필수, 하나만, 기존 형식·바이트 비교 유지 | 규칙 14, IDEMPOTENCY_CONTRACT §1 |
| 매핑 | 201 / 409 `SEAT_UNAVAILABLE` / 429 `QUOTA_EXCEEDED` / 422 `SALE_EVENT_SCOPE_REJECTED` / 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` / 422 `IDEMPOTENCY_KEY_REUSED` / 503 / 500 | HOLD_HTTP_CONTRACT §6 |
| **C3** | 진행 중·소유권 상실 = `409` + `IDEMPOTENCY_REQUEST_IN_PROGRESS` + `Retry-After` 초 단위(초기 1초, 가정) | 규칙 21 확장 |
| 실행 = 재생 | 같은 상태 코드, 같은 업무 결과 | 규칙 17 |
| 로그·오류율 | 업무 거절·진행 중은 정상(ERROR·오류율 제외), 5xx 만 서버 장애 | 규칙 21·22 |
| 토큰 분리 | 액세스 토큰(신원)과 입장 토큰(회차 권한)을 키·iss·aud·typ·token_use·헤더로 분리, 요청마다 `sub` 일치·`evt` = 해석 회차 | ADR-015 §3~§5 |
| 401/403 | 401 = 자격 증명 없음·무효·만료, 403 = 유효하지만 사용자·회차 불일치 | ADR-015 §5 |
| 개발 발급 | 기본 비활성, 운영 프로필에서 켜지면 기동 실패, 별도 issuer | ADR-015 §6 |
| 우회 경로 | 운영 경로에 인증·입장 우회 없음. 대기열 전 테스트 대역은 테스트 소스 한정 | ADR-015 §6 |

### 제안 (구현 Task 에서 조정 가능)

- 프록시는 경로를 바꾸지 않고 서비스가 `/api/reservations/**` 를 그대로 매핑
- 400 의 `details`(필드 경로·규칙 이름만), 503/500 의 예외 타입 분류 기준, `Idempotent-Replayed` 디버깅 헤더
- 입장 토큰 claim 모델·검증기를 `modules/queue-token` 에 둔다
- 입장 토큰 서명을 ES256 으로 바꾸는 재검토 (Phase 2)

### 구현 전에 결정이 필요한 것

| # | 항목 | 권장안 | 출처 |
|---|---|---|---|
| **D1** | 재생 응답과 traceId | B — 재생 범위 = 상태+본문, traceId 는 `X-Trace-Id` 헤더, 재생 대상 업무 실패 본문은 규칙 24 의 예외 | HOLD_HTTP_CONTRACT §7.3 |
| **D2** | 성공 본문의 `expiresAt` | 넣지 않고 홀드 조회 API 로 | §7.1 |
| **D3** | 429 `QUOTA_EXCEEDED` 와 규칙 22 의 `Retry-After` | 409 로 옮기거나 429 유지 + 가정 간격. I-12 가 429 를 쓰고 있어 임의로 바꾸지 않음 | §8 |
| **D4** | 입장 회차 대조를 TX1 안에서 다시 할지 | 명령에 기대 회차를 실어 TX1 안에서 대조 | §3, §8 |
| D-A1 | 간이 JWT 발급의 신원 확인 | 비밀번호 해시 + 발급 엔드포인트 | ADR-015 §6 |
| D-A2~A5 | 서명 알고리즘, `evt` 식별자 공간, 수명·시계 오차, JWT 라이브러리 | ADR-015 §8 | |
| C4 | 리스·보존 수치 | 미결정 유지 | ADR-009 |

---

## 4. ★ 최초 응답 재생과 traceId — 검토 결과

- 규칙 17(최초 응답 재생)·규칙 24(오류 본문의 traceId)·I-18 T-4(바이트 동일 응답)는 **지금 저장 모델로는 함께 성립하지 않는다.**
  저장 결과에 `message`·`traceId` 가 없고, `traceId` 는 요청마다 다르다.
- 재요청의 `traceId` 를 재생 본문에 넣은 응답을 "동일 응답" 이라고 하지 않는다 — 업무 결과만 같다.
- 해결안 A(최초 응답 바이트 저장, V8 필요), B(재생 범위를 상태+본문으로 정하고 traceId 를 헤더로, migration 없음),
  C(I-18 기준을 업무 결과 동일로 완화)를 비교했다. 권장은 **B**.
- **B 는 규칙 24 의 예외를, C 는 I-18 검증 기준의 완화를 요구한다.** 그래서 규칙·불변식은 바꾸지 않고 **D1** 로 남겼다.
  INVARIANTS I-18 에 "검토 중" 메모만 달았다.

---

## 5. 다음 구현 단위

각 단위는 독립적으로 검증 가능하고, 앞 단위가 끝나야 다음이 의미를 갖는다.

| 순서 | Task(가칭) | 범위 | 완료 기준 | 선행 조건 |
|---|---|---|---|---|
| 0 | 결정 | D1·D2·D3·D4 와 ADR-015 D-A5(JWT 라이브러리) 결정, ADR-015 상태 갱신 | 문서 반영 | — |
| 1 | **2J-B 액세스 토큰 검증** | 의존성 등록(ADR), 액세스 토큰 검증기·필터, 401 응답 형식, traceId 필터(D1 결과 반영), 테스트용 토큰 도우미(테스트 소스) | 시나리오 A1 · 인증 없이는 어떤 경로도 통과하지 않음 | 0 |
| 2 | **2J-C 입장 토큰 검증** | `queue-token` 모듈의 claim·검증기, 입장 필터, 사용자 대조 403 | 시나리오 A2·A3·A6, 키 분리·용도 혼동 거부 | 1 |
| 3 | **2J-D 선점 HTTP 연결** | 컨트롤러, 헤더·본문 검증, 회차 사전 해석·입장 회차 대조(+D4), 결과 매핑·`Retry-After`·오류 본문, 5xx 분류, 메트릭 `code` 태그 | 시나리오 A4·A5·V1~V4·I1~I6·F1~F3 (MockMvc + MySQL) | 2 |
| 4 | **2J-E 간이 발급** | D-A1 에 따른 사용자 저장소·비밀번호 검증·발급 엔드포인트(migration), 개발 전용 발급(기본 비활성) | 임의 userId 로 토큰을 받을 수 없음, 운영 프로필에서 개발 발급 기동 실패 | 1 |
| 후속 | 홀드 조회 API(D2), blocklist(ADR-011), 감사 로그(규칙 32), 보존 기간 삭제 | | | |

---

## 6. 검증

- 문서와 코드의 일치 — §2 의 사실은 `apps/reservation-service/src/main`·`modules/*/src/main` 과 `gradle/libs.versions.toml` 을
  직접 확인했다 (컨트롤러·보안·JWT 의존성·traceId 없음, `SeatCount.MAX = 4`, `IdempotencyKey` 패턴, 저장 결과 필드)
- 상대 링크와 `git diff --check` 를 확인했다
- **문서와 Javadoc 주석만 바꿨으므로 테스트를 새로 만들거나 전체 테스트를 다시 돌리지 않았다.** 동작 코드는 바뀌지 않았다.
  마지막 전체 check 는 TASK-002I-C §5 의 1113개 통과(2026-10-22) 다
