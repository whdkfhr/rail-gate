# 결정 — 판매 이벤트 단위 quota 범위 계약 (Task 2G-D)

> TASK-002G-D · 2026-08-28
> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.6·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [CLAUDE.md](../../CLAUDE.md) 규칙 8·26·27·30
> 선행: [2G-A](TASK-002G-user-hold-quota-race.md) · [2G-B](TASK-002G-B-quota-lock-order.md) · [2G-C](TASK-002G-C-expiry-quota-attribution.md)

## 1. 문제 정의

I-12(1인당 4석 상한)의 **범위(scope)** 가 정해지지 않아 운영 구현이 세 Task 째 막혀 있었다.
2G-A 는 "결정 필요" 로 남겼고, 2G-B·2G-C 도 같은 이유로 운영 반영을 보류했다.

이 Task 는 **정책 결정과 계약 검증**이다. 운영 테이블·저장소·서비스를 만들지 않는다.

### FR-2.6 의 범위가 불명확했던 이유

| 근거 | 내용 |
|---|---|
| `REQUIREMENTS.md` FR-2.6 | "한 사용자는 동시에 4석을 초과해 선점할 수 없다" — **범위가 없다** |
| `INVARIANTS.md` I-12 예시 | `WHERE user_id=:u AND event_id=:e` — **`event_id` 를 쓴다** |
| `V1__seat_inventory.sql` | `schedule_id` 만 있다. 주석: "train_schedule 테이블은 후속 Task 에서 추가" |
| reservation 영역 | **판매 이벤트 모델도 `train_schedule` 테이블도 없다** |

문서는 `event_id` 를 말하는데 스키마에는 `schedule_id` 밖에 없다. 둘을 같은 것으로 볼 근거가 없어
기존 실험은 전부 중립적인 `quota_scope_id` 를 썼다.

---

## 2. ★ 운행편 범위의 우회 재현

`schedule_id` 를 범위 키로 쓰면 **운행편마다 별도의 카운터 행**이 생긴다.

### 결과

| 관측 | 값 |
|---|---|
| 운행편 A 카운터 | 3 — 상한을 지킨 것처럼 보인다 |
| 운행편 B 카운터 | 3 — 따로 관리된다 |
| **같은 판매 이벤트의 실제 활성 좌석** | **6석** — I-12 위반 |

4석씩 두 번 잡는 변형에서는 **8석**이 됐다. 운행편이 100개면 최대 400석이다.

**FR-2.6 의 목적은 사재기 방지인데, 운행편만 바꾸면 그 목적이 달성되지 않는다.**
좌석 행 잠금도 카운터 행 잠금도 이것을 막지 못한다 — 애초에 **다른 행**을 잠그기 때문이다.

> 이 테스트가 통과한다는 것은 **우회를 관측했다**는 뜻이지 그 설계가 옳다는 뜻이 아니다 (규칙 27).

---

## 3. 비교한 세 가지 범위

| 범위 | 의미 | 판단 |
|---|---|---|
| **`schedule_id`** | 운행편마다 4석 | ❌ **기각** — §2 의 우회. 운행편 수만큼 상한이 배수로 늘어난다 |
| **사용자 전역** | 계정당 4석 (모든 판매 회차 합산) | ❌ **기각** — 아래 |
| **`sale_event_id`** | 같은 판매 이벤트의 모든 운행편 합산, 이벤트 간 독립 | ✅ **채택** |

### 사용자 전역이 지나치게 넓은 이유

- 서로 **무관한 판매 회차가 서로의 quota 를 잠식**한다. 추석 표 4석을 잡고 있으면 설 판매에서 한 석도 못 잡는다
- 과거 회차나 동시에 열린 다른 회차와 불필요하게 결합된다
- **판매 회차별 운영·초기화가 어렵다.** 회차가 끝나도 그 몫을 어떻게 회수할지 경계가 없다

### `sale_event_id` 를 채택한 이유

- **운행편 변경을 통한 상한 우회를 차단**한다 (§2 의 문제를 정확히 해결)
- **명절 판매 회차라는 업무 개념과 일치**한다. "이번 추석 표는 1인 4석" 이 실제 정책 문장이다
- **대기열 입장 범위와 quota 범위를 맞출 수 있다.** 목표 대기열 설계가 이벤트 단위 입장 제어를 쓴다
- **판매 이벤트 종료 후 quota 정리 범위가 명확**하다. `WHERE sale_event_id = ?` 로 끝난다

---

## 4. 확정한 계약

### 판매 이벤트(SaleEvent)의 업무 정의

> 관리자가 **하나의 오픈 시각과 입장 정책으로 운영하는 예매 판매 회차**.

예: `2026년 추석 승차권 1차 판매`, `2026년 설 승차권 판매`.
하나의 판매 이벤트에는 **여러 열차 운행편**이 포함될 수 있다.

### quota 범위 규칙

> 한 사용자는 **동일한 판매 이벤트 전체에서** `HELD` 또는 `PAYING` 상태의 좌석을 합산해
> 최대 **4석(P-2)** 까지만 가질 수 있다.

| 규칙 | 내용 |
|---|---|
| 여러 운행편 합산 | 같은 이벤트의 서로 다른 운행편도 **하나의 카운터**로 합산 |
| 왕복·다구간 | 서로 다른 운행편을 선택해도 **같은 이벤트면 합산** |
| 이벤트 간 독립 | 서로 다른 판매 이벤트의 quota 는 **완전히 별개** |
| `SOLD` 제외 | 확정된 좌석은 더 이상 활성 선점이 아니다 (2B) |
| `AVAILABLE` 제외 | 점유하지 않은 좌석 |

### 식별자 이름 — `SaleEventId` / `sale_event_id`

일반적인 `EventId` 를 쓰지 않는다.

- **도메인 이벤트**(`SeatHeld`, `ReservationConfirmed` …)와 혼동된다
- **Kafka 이벤트**와 혼동된다
- **대기열 이벤트**와 구분이 필요하다 — 관련은 있지만 같은 것이 아니다

`SaleEvent` 는 **판매 회차**를 뜻한다는 것이 이름에서 드러난다.

### 관계

```
SaleEvent 1 ─── N TrainSchedule 1 ─── N SeatInventory
    │
    └── + User ──▶ UserHoldQuota  (saleEventId, userId)
```

- `TrainSchedule` 은 **정확히 하나**의 `SaleEvent` 에 속한다
- `SeatInventory` 는 `TrainSchedule` 을 참조한다
- `UserHoldQuota` 는 `(saleEventId, userId)` 단위다

> **판매가 시작되거나 좌석 재고가 만들어진 뒤 `TrainSchedule` 의 `SaleEvent` 소속을 바꾸지 않는다.**
> 바꾸면 **이미 쌓인 quota 의 의미가 달라진다** — 그 사용자가 어느 이벤트에서 몇 석을 잡고 있었는지
> 되짚을 수 없다. 테스트 fixture 도 재배정을 거부한다.

---

## 5. 향후 운영 스키마 초안 (문서에만)

**마이그레이션 파일로 추가하지 않았다.** 형태 비교용이다.

```sql
-- 판매 회차
sale_event(
  id          BIGINT PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  opens_at    DATETIME(3)  NOT NULL,
  closes_at   DATETIME(3)  NULL,
  status      VARCHAR(16)  NOT NULL,   -- SCHEDULED | OPEN | CLOSED
  ...
);

-- 열차 운행편 — 정확히 하나의 판매 회차에 속한다
train_schedule(
  id            BIGINT PRIMARY KEY,
  sale_event_id BIGINT NOT NULL,
  ...,
  KEY idx_train_schedule_sale_event (sale_event_id)
);

-- 1인당 활성 선점 카운터
user_hold_quota(
  sale_event_id BIGINT   NOT NULL,
  user_id       BIGINT   NOT NULL,
  held_seats    SMALLINT NOT NULL DEFAULT 0,
  updated_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (sale_event_id, user_id),
  CONSTRAINT ck_user_hold_quota_range CHECK (held_seats >= 0 AND held_seats <= 4)
);
```

**PK 를 `(sale_event_id, user_id)` 로 두는 이유**는 잠금 키와 정렬 순서를 같게 만들기 위해서다(§6).

`seat_inventory` 에 `sale_event_id` 를 비정규화해 넣을지는 **결정하지 않았다.**
`train_schedule` 조인 한 번을 줄이는 대신 소속 변경 시 정합성 문제가 생긴다 — 후속 Task 의 판단이다.

---

## 6. 잠금 키와 결정적 순서

Task 2G-B·2G-C 가 정한 **quota → seat** 원칙은 그대로다. 바뀌는 것은 **키의 구성**뿐이다.

| | 이전(실험) | 확정 |
|---|---|---|
| quota 키 | `(quota_scope_id, user_id)` | **`(sale_event_id, user_id)`** |
| 잠금 순서 | scope → user → seat | **saleEvent → user → seat** |
| 그룹 안 좌석 | `seatId` 오름차순 | 동일 (규칙 2) |

여러 quota 행을 잠그는 경로(만료 배치, 2G-C)는 `(saleEventId, userId)` **오름차순**으로 잠근다.
테스트가 그 정렬을 고정한다.

---

## 7. 대기열 입장 범위와의 관계 (목표 설계)

> **현재 `queue-service` 는 골격 단계다.** Spring Boot 애플리케이션 클래스와 컨텍스트 로드
> 테스트만 있고 `queue-domain`·`queue-token` 에는 소스가 없다. Redis 키 계약(`rg:{evt:E1}:*`)과
> 입장 토큰의 `evt` 클레임은 `ARCHITECTURE.md` 의 **목표 설계**이며 구현되지 않았다.

목표 대기열 설계는 **이벤트 단위 입장 제어**를 사용한다. quota 범위를 판매 이벤트로 맞추면
두 경계가 같아진다.

- 입장 토큰의 `evt` 가 곧 `sale_event_id` 가 될 수 있다
- "이 회차에 입장했고, 이 회차에서 4석까지" 라는 하나의 문장이 된다

> **대기열의 `event` 식별자와 `sale_event_id` 를 동일하게 쓸지, 별도 매핑할지는 후속 작업에서
> 결정한다.** 대기열 이벤트가 판매 회차보다 더 잘게 쪼개질 여지가 있고(예: 노선별 입장 제어),
> 그 결정은 Phase 2 의 대기열 Task 몫이다. 지금은 **방향만 기록한다.**

---

## 8. 검증 결과

`UserHoldQuotaScopeContractTest` — 실제 MySQL 8.4 Testcontainers, **15 tests**.

### §1 운행편 범위 우회 재현 (2)

| 검증 | 결과 |
|---|---|
| 운행편을 범위로 쓰면 같은 판매 이벤트에서 6석 허용 | 통과 *(우회 관측)* |
| 두 운행편이 같은 이벤트인데도 카운터가 분리 → 8석 | 통과 *(우회 관측)* |

### §2 판매 이벤트 범위 (7)

| 검증 | 결과 |
|---|---|
| 두 운행편에서 각각 3석 **동시** 요청 → 하나만 성공, quota 3, 활성 ≤ 4 | 통과 |
| 2석 + 2석 → 합산 정확히 4 | 통과 |
| 이미 3석 보유 후 다른 운행편 2석 → 거부, quota 3 유지, 좌석 미점유 | 통과 |
| 서로 다른 판매 이벤트에서 각각 4석 → 둘 다 독립 성공 | 통과 |
| 한 이벤트가 상한이어도 다른 이벤트 요청에 영향 없음 | 통과 |
| 다른 사용자는 같은 이벤트에서도 각자 4석 | 통과 |
| **활성 quota 대상은 `HELD`·`PAYING` 뿐** — `SOLD`/`PAYING`/`HELD`/`AVAILABLE` 네 상태를 동시에 만들어 확인 | 통과 |

**`SOLD` 제외는 상태를 직접 UPDATE 하지 않고 운영 결제 경로로 검증했다** —
`holdAll` → `startPayment` → `confirm` 을 거쳐 실제 `SOLD` 를 만든 뒤,
`SOLD`/`PAYING`/`HELD`/`AVAILABLE` 이 동시에 존재하는 상태에서 활성 좌석이 2(=HELD 1 + PAYING 1)임을 확인했다.

> 이 테스트가 검증하는 것은 **좌석 상태 기반 활성 quota 분류**뿐이다.
> 확정 시 quota 카운터를 줄이는 경로는 아직 구현 범위가 아니어서(2G-C 후속 과제)
> 좌석이 `SOLD` 가 돼도 카운터는 그대로다 — **counter 감소나 reconciliation 완성 여부는 주장하지 않는다.**

동시 요청 테스트는 `CyclicBarrier` 로 시작점을 맞췄다. **원자적 상한 검사 자체는
Task 2G-A 가 이미 검증했고**, 이 Task 가 더하는 것은 그 검사가 적용될 **범위**뿐이다.
순차 실행 결과만으로 동시 요청 안전성을 새로 증명했다고 주장하지 않는다 (규칙 30).

### §3 매핑 계약과 잠금 키 (6)

| 검증 | 결과 |
|---|---|
| 매핑되지 않은 운행편은 resolver 에서 실패 | 통과 |
| **미매핑 운행편이 실제 선점 흐름에서 부수 효과 없이 거부** — quota 행 수·합계 불변, 좌석 `AVAILABLE`·`hold_id`/`held_by`/`expires_at` null·`version` 0 | 통과 |
| `scheduleId` 를 `saleEventId` 로 암묵 대입하지 않음 | 통과 |
| 하나의 운행편은 정확히 하나의 판매 이벤트 (재배정 거부) | 통과 |
| quota 키가 `(saleEventId, userId)` 오름차순 정렬 | 통과 |
| 같은 이벤트의 여러 운행편이 하나의 quota 키로 모임 | 통과 |

---

## 9. 운영 마이그레이션을 아직 만들지 않는 이유

1. **`SaleEvent` aggregate 와 `train_schedule` 테이블이 먼저다.** `user_hold_quota` 의 FK 대상이 없다.
2. **`seat_inventory` 의 `sale_event_id` 비정규화 여부가 미결정**이다 (§5).
3. **만료 배치의 운영 계약이 미결정**이다 (2G-C §10) — 배치 원자성, quota 행 누락 처리,
   `held_by` NULL 처리, 사용자별 트랜잭션 분리.
4. 이 프로젝트는 V1·V2·V3 를 "적용됨, 수정 금지" 로 다룬다. 마이그레이션은 되돌리기 어려운 결정이다.

---

## 10. 검증된 것과 검증하지 않은 것

### 검증된 것

- 운행편을 quota 범위로 쓰면 **같은 판매 이벤트에서 상한이 우회된다** (6석·8석 관측)
- 판매 이벤트 범위에서는 여러 운행편이 **하나의 카운터로 합산**된다
- 동시 요청에서 상한을 넘는 조합은 하나만 통과한다
- 서로 다른 판매 이벤트의 quota 는 **완전히 독립**이다
- 다른 사용자는 같은 이벤트에서도 각자의 행을 쓴다
- `SOLD`·`AVAILABLE` 은 활성 quota 에서 제외된다
- 매핑되지 않은 운행편은 좌석을 건드리기 전에 실패한다
- quota 키가 `(saleEventId, userId)` 로 결정적으로 정렬된다

### 검증하지 않은 것

- **운영 `SaleEvent` 모델의 동작** — 만들지 않았다
- **`train_schedule` 조인 비용** — 운행편에서 판매 이벤트를 얻는 경로의 비용을 측정하지 않았다
- **`seat_inventory` 비정규화 여부**의 트레이드오프
- **대기열 이벤트 식별자와 `sale_event_id` 의 동일성** — 방향만 기록했다
- **판매 이벤트 종료 시 quota 정리 절차**
- 2G-C 가 남긴 미검증 항목(다중 스위퍼 quota 이중 감소, SQL 수 증가 비용, 전체 deadlock-free,
  `confirm` 경로 quota 감소)은 **그대로 남아 있다**

---

## 11. 남은 위험

| 항목 | 내용 |
|---|---|
| **I-12 는 여전히 운영 미구현** | 이 Task 는 범위를 결정했을 뿐이다. 운영 테이블·저장소·서비스가 없다 |
| **`SaleEvent` 모델 부재** | quota 의 FK 대상이 없어 마이그레이션을 쓸 수 없다 *(→ [2G-E-A](TASK-002G-E-A-sale-event-domain.md) 에서 도메인 모델을 만들었다. 테이블은 여전히 없다)* |
| **소속 변경 금지의 강제 수단 없음** | 테스트 fixture 는 거부하지만 운영에서는 FK 만으로 막히지 않는다. 판매 시작 후 변경을 막는 상태 규칙이 필요하다 *(→ [2G-E-A](TASK-002G-E-A-sale-event-domain.md) §4 는 상태 규칙 대신 **구조적 불변**을 택했다. 오픈 전 재배정도 안전하지 않기 때문이다. DB 차원 차단은 미해결)* |
| **비정규화 여부 미결정** | 매 선점마다 `train_schedule` 조인이 필요할 수 있다. 측정하지 않았다 |
| **대기열 범위와의 정합** | `queue-service` 가 골격 단계라 대기열 쪽 계약 자체가 아직 없다. 두 식별자를 같게 둘지 미결정이며, 어긋나면 "입장은 됐는데 quota 범위가 다른" 상황이 생긴다 |
| **판매 이벤트 종료 후 정리** | 카운터를 언제 어떻게 비울지 정해지지 않았다 |
| 2G-C 잔여 위험 | 다중 스위퍼 이중 감소, SQL 수 증가, drift 시 배치 중단 정책, `confirm` 경로 quota 감소, 전체 deadlock-free 미검증 |

---

## 12. 후속 Task 범위

1. **Task 2G-E — `SaleEvent` / `train_schedule` 운영 모델**
   - ~~`SaleEvent` aggregate 와 상태(SCHEDULED/OPEN/CLOSED)~~
     → [2G-E-A](TASK-002G-E-A-sale-event-domain.md) 완료
   - `train_schedule` 마이그레이션과 `sale_event_id` FK — **남음 (2G-E-B)**
   - ~~판매 시작 후 소속 변경 금지를 상태 규칙으로 강제~~
     → [2G-E-A](TASK-002G-E-A-sale-event-domain.md) §4 에서 **구조적 불변**으로 대체.
     DB 차원 차단은 **남음**
   - `seat_inventory` 비정규화 여부 측정 후 결정 — **남음 (2G-E-B)**
2. **Task 2G-F — 만료 배치 운영 계약** (2G-C §10 의 8가지)
3. **Task 2G-G — `user_hold_quota` 마이그레이션과 운영 저장소**
4. **Task 2H — 애플리케이션 서비스와 트랜잭션 경계 소유**
   - `QuotaExceededException` 도입, 429 매핑은 API Task 로
5. 정합성 검사 쿼리를 `load-test/verify/` 에 추가

---

## 13. 재현 명령

```bash
./gradlew :modules:reservation-infra:test --tests "*UserHoldQuotaScopeContractTest"
```

Docker 가 실행 중이어야 한다. 테스트 전용 테이블 `task2g_user_hold_quota` 는 테스트가 직접 만든다
(운영 마이그레이션 없음).
