# 적용 — SaleEvent / TrainSchedule 운영 스키마와 저장소 (Task 2G-E-B2)

> TASK-002G-E-B2 · 2026-08-31
> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.6·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [CLAUDE.md](../../CLAUDE.md) 규칙 1·3·7·25·26·27·30
> 선행: [2G-D](TASK-002G-D-quota-scope-contract.md) · [2G-E-A](TASK-002G-E-A-sale-event-domain.md) ·
> [2G-E-B1](TASK-002G-E-B1-schema-decision.md)

## 1. 문제 정의

[2G-E-B1](TASK-002G-E-B1-schema-decision.md) 이 실험으로 결정한 것을 운영 코드에 적용한다.

| B1 의 결정 | 이 Task |
|---|---|
| 정규화 채택 (`seat_inventory` 에 `sale_event_id` 를 넣지 않는다) | V4 + 조인 저장소로 적용 |
| `seat_inventory.schedule_id` FK 채택 | V5 로 적용 |
| 트리거는 계정 분리 조건부 | **적용하지 않음** (§7) |
| 선택지 A — 단계 분리 migration | 적용하되 **구조를 정정함** (§4) |

**이 Task 는 적용 단계다.** 여전히 만들지 않은 것: 애플리케이션 서비스, REST API,
`user_hold_quota` 운영 구현, 포트 인터페이스, 프론트엔드.
**I-12 는 여전히 운영에 구현되지 않았다.**

---

## 2. RED 기록 (규칙 27)

세 단계로 실패를 먼저 남겼다.

### RED-1 — 마이그레이션 부재

```
./gradlew :modules:reservation-infra:test --tests "*SaleEventSchemaMigrationTest"
  org.flywaydb.core.api.exception.FlywayException  (MigrationInfoServiceImpl.validateTarget)
  → target=4 로 지정할 버전이 없다
```

### RED-2 — 저장소 부재

```
./gradlew :modules:reservation-infra:compileTestJava
  error: cannot find symbol  symbol: class JdbcSaleEventRepository
  error: cannot find symbol  symbol: class JdbcTrainScheduleRepository
  error: cannot find symbol  symbol: class JdbcSaleEventScopeRepository
  error: cannot find symbol  symbol: class SaleEventTransitionOutcome
```

### ★ RED-3 — 구현 도중 드러난 것들

**여기가 이 Task 에서 가장 중요한 부분이다.** 계획대로 짰는데 실패한 네 가지이며,
넷 다 B1 이 예상하지 못했던 것이다. 각각 §5·§4·§6 에서 다룬다.

| 관측된 실패 | 뜻 |
|---|---|
| 4석 해석이 **609행**을 읽었다 (기대 ≤ 16) | 통계가 stale 하면 **조인 순서가 뒤집힌다** |
| `Table 'migration_guard_v5' already exists` | 재시도 시 **같은 커넥션이 재사용**된다 |
| `FlywayValidateException: ... run repair` | 원인을 없애도 **repair 없이는 재개되지 않는다** |
| 없는 좌석을 섞었는데 **범위가 반환됐다** | 누락 좌석을 조용히 무시하고 있었다 |

---

## 3. V4 — 테이블 생성

`sale_event` 와 `train_schedule` 을 만든다. **`seat_inventory` 는 건드리지 않는다.**

```sql
sale_event(
  id, name,
  opens_at  DATETIME(3) NOT NULL,   -- 오픈 판정 기준
  closes_at DATETIME(3) NULL,       -- 마감 "예정" 시각. 판정 근거가 아니다
  status    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT chk_sale_event_status      CHECK (status IN ('SCHEDULED','OPEN','CLOSED')),
  CONSTRAINT chk_sale_event_sale_period CHECK (closes_at IS NULL OR closes_at > opens_at)
);

train_schedule(
  id, sale_event_id BIGINT NOT NULL,
  KEY idx_train_schedule_sale_event (sale_event_id),
  CONSTRAINT fk_train_schedule_sale_event FOREIGN KEY (sale_event_id) REFERENCES sale_event (id)
);
```

- **`ascii_bin`** 인 이유는 `seat_inventory.status` 와 같다. 테이블 기본 콜레이션
  `utf8mb4_0900_ai_ci` 는 대소문자를 구분하지 않아, 그대로 두면 `'open'` 이 CHECK 를 통과하고
  조건부 UPDATE 의 `WHERE status='OPEN'` 도 소문자 행에 매칭된다. 테스트가 이것을 고정한다.
- **`DATETIME(3)`** — 오픈 판정이 `NOW(3)` 기준이라 밀리초가 없으면 같은 초 안의 순서를
  구분할 수 없다 (규칙 7).
- **`seat_inventory` 에 `sale_event_id` 를 넣지 않았다** — B1 §8 의 결정. 테스트가
  `information_schema` 로 그 컬럼의 부재를 확인한다.

---

## 4. ★ B1 §12 의 정정 — "V5 backfill" 은 마이그레이션이 될 수 없다

B1 은 `V4 생성 → V5 backfill → V6 FK` 를 계획했다. **구현하면서 V5 를 파일로 만들 수 없다는
것이 드러났다.**

backfill 은 "이 운행편이 어느 판매 회차에 속하는가" 를 적는 일인데,
**이 저장소에는 그 답의 원천이 없다.** 운영 데이터도, 매핑 표도, 등록 API 도 없다.
마이그레이션 파일이 답을 가지려면 지어내는 수밖에 없고, 그것은 B1 §12 가 재현해 금지한
세 지름길과 정확히 같다. 빈 V5 를 넣는 것은 아무 일도 하지 않으면서 단계를 밟은 것처럼 보이게 한다.

그래서 backfill 을 **마이그레이션이 아니라 V4 와 FK 사이의 명시적 운영 단계**로 옮겼다.
FK 마이그레이션은 V6 이 아니라 **V5** 가 된다.

```
V4                sale_event · train_schedule 생성          (자동)
── 운영 단계 ──    운영자가 명시적 매핑을 입력                  (수동, 마이그레이션 아님)
V5                orphan 0 검증 → seat_inventory FK 추가     (자동)
```

### 배포 절차

```bash
flyway migrate -target=4          # 테이블만 만든다
# ── 운영자가 sale_event / train_schedule 에 매핑을 입력한다 ──
flyway migrate                    # orphan 검증 후 FK 추가
```

**앞선 시도가 실패했다면 그 사이에 `flyway repair` 가 필요하다** (§6).

### 실패 정책

orphan 이 한 건이라도 있으면 V5 는 **실패한다.** FK 추가만으로도 실패하지만 그 메시지는
외래 키 위반이라고만 말한다. 원인을 드러내려고 가드를 앞에 뒀다.

```sql
DROP TEMPORARY TABLE IF EXISTS migration_guard_v5;

CREATE TEMPORARY TABLE migration_guard_v5 (
    unmapped_train_schedule_count INT NOT NULL,
    CONSTRAINT chk_no_unmapped_train_schedule CHECK (unmapped_train_schedule_count = 0)
);

INSERT INTO migration_guard_v5 (unmapped_train_schedule_count)
SELECT COUNT(DISTINCT s.schedule_id)
  FROM seat_inventory s
  LEFT JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE ts.id IS NULL;

DROP TEMPORARY TABLE migration_guard_v5;
```

제약 이름이 곧 실패 원인이다 — `Check constraint 'chk_no_unmapped_train_schedule' is violated`.

**세션 임시 테이블이라 실패해도 스키마에 흔적이 남지 않는다.** 테스트로 확인한 것:
매핑 없이 V5 를 돌리면 실패하고, **좌석 8행이 그대로 남고**, FK 도 붙지 않는다.

### ★ `DROP TEMPORARY TABLE IF EXISTS` 가 필요한 이유 (RED-3)

처음에는 `CREATE` 만 뒀다. 재실행 테스트가 이렇게 실패했다.

```
Failed to execute script V5__add_seat_inventory_schedule_fk.sql
Message : Table 'migration_guard_v5' already exists
```

Flyway 가 재시도할 때 **커넥션 풀에서 같은 커넥션을 받으면** 앞선 실패의 임시 테이블이
아직 그 세션에 살아 있다. 임시 테이블은 "커넥션이 닫히면 사라진다" 이지
"문장이 끝나면 사라진다" 가 아니다. 가드 자체가 재실행 안전해야 한다.

### 금지 사항은 그대로다

B1 §12 가 재현한 세 지름길 — 기본 회차 몰아넣기, `scheduleId` 대입, orphan 삭제 —
은 전부 FK 를 통과시켜 마이그레이션을 초록색으로 만든다. **하지 않는다.**
V4·V5 의 주석에 그 이유를 남겼다.

---

## 5. ★ 조인 순서를 고정하지 않으면 B1 의 결정이 무너진다

### 관측

운영 `seat_inventory` 에 좌석 600행(운행편 3개 × 200석)을 넣고, **통계를 갱신하지 않은**
상태에서 4석 요청을 해석했다.

| SQL | driving table | 계획 | 읽은 행 |
|---|---|---|---|
| `JOIN` (통계 stale) | `train_schedule` | `index` → `ref uk_seat_inventory_seat` | **520 ~ 609** |
| `STRAIGHT_JOIN` | `seat_inventory` | `range PRIMARY` → `eq_ref PRIMARY` | **5 ~ 7** |
| `JOIN` (ANALYZE 직후) | `seat_inventory` | `range PRIMARY` → `eq_ref PRIMARY` | 5 |

옵티마이저가 `train_schedule` 을 먼저 훑고, 운행편마다
`uk_seat_inventory_seat (schedule_id, seat_no)` 로 좌석을 따라 들어간다.
그러면 읽는 행 수가 **요청 좌석 수가 아니라 전체 좌석 수**에 비례한다.

```
-> Covering index scan on ts using idx_train_schedule_sale_event (rows=3)
   -> Covering index lookup on s using uk_seat_inventory_seat (schedule_id=ts.id)
      (actual rows=200 loops=3)
```

### 왜 중요한가

**B1 §7 의 측정(7행)은 픽스처가 `ANALYZE TABLE` 을 돌린 직후에 나온 값이었다.**
운영에서는 통계가 계속 흔들리므로 그 조건이 유지되지 않는다.

정규화를 택한 근거는 "비용이 요청 좌석 수(P-2: 최대 4)에 갇힌다" 였다.
**그 근거를 유지하려면 조인 순서를 고정해야 한다.** 고정하지 않으면 B1 의 결정 자체가
성립하지 않는다 — 좌석이 늘수록 quota 범위 해석 비용이 선형으로 나빠진다.

CLAUDE.md 규칙 3 이 `IN` 절 UPDATE 에 `FORCE INDEX (PRIMARY)` 를 요구하는 것과 같은 문제다.
옵티마이저의 선택에 계약을 맡기지 않는다. 다만 여기서 고정할 것은 인덱스가 아니라 **조인 순서**다.

`ANALYZE TABLE` 을 주기적으로 도는 것으로 대신하지 않는다. 그것은 "대체로 맞는 계획" 을
기대하는 것이지 보장이 아니다.

### 적용한 SQL

```sql
SELECT ts.sale_event_id
  FROM seat_inventory s
  STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.id IN (?, ?, ?, ?);
```

테스트가 **저장소가 실행하는 문자열을 그대로** EXPLAIN 하고, 구동 테이블이 `s` 인지,
`ts` 가 `eq_ref` 인지, 양쪽 모두 `PRIMARY` 인지를 단정한다.
고정하지 않은 형태(`naiveResolveSql`)는 비교용으로 남겨 두고 함께 측정한다 —
**CI 실측 재현: 고정 5행 / 미고정 608행.**

> 미고정 쪽이 통과한다는 것은 우회를 관측했다는 뜻이지 쓸 만하다는 뜻이 아니다 (규칙 27).

### `DISTINCT` 를 뺀 이유 (RED-3)

처음에는 `SELECT DISTINCT` 였다. 그러면 **없는 좌석이 섞여도 통과한다** —
네 좌석을 요청했는데 세 좌석만 존재해도 회차가 하나로 모이면 범위가 반환됐다.
호출부는 "네 좌석의 범위" 를 받았다고 믿지만 실제로는 세 좌석의 범위이고,
quota 계산이 요청과 다른 집합 위에서 이뤄진다.

좌석마다 한 행을 받아 **개수를 세도록** 바꿨다. 중복 좌석 입력도 `IN` 절에서 합쳐져
개수를 흐리므로 거부한다.

---

## 6. ★ 실패한 마이그레이션은 `repair` 없이 재개되지 않는다

매핑을 넣어 **원인을 없앤 뒤에도** `migrate` 만으로는 재개되지 않는다.

```
FlywayValidateException: Validate failed: Migrations have failed validation
Detected failed migration to version 5 (add seat inventory schedule fk).
Please remove any half-completed changes then run repair to fix the schema history.
```

Flyway 가 실패한 시도를 `flyway_schema_history` 에 남기고 검증에서 거부한다.

**우리 V5 는 절반만 적용된 변경을 남기지 않는다.** 가드가 FK 추가보다 먼저 실패하고,
가드 테이블은 세션 임시 테이블이다. 그래서 스키마를 손볼 것은 없고 **이력만 정리하면 된다.**

```bash
flyway repair
flyway migrate
```

테스트가 두 가지를 각각 고정한다 — `repair` 없이는 거부된다는 것, `repair` 후에는
같은 파일이 그대로 적용되고 좌석 8행이 보존된다는 것.

**이 단계를 배포 절차에 적어 두지 않으면 운영자가 여기서 막힌다.**

---

## 7. 저장소

`modules/reservation-infra` 에 Spring JDBC 저장소 세 개를 만들었다.

### `JdbcSaleEventRepository`

```java
Optional<SaleEvent> findById(SaleEventId id)
SaleEventTransitionOutcome open(SaleEventId id)
SaleEventTransitionOutcome close(SaleEventId id)
```

**조건부 UPDATE 로 전이하고 `affected_rows` 로 판정한다** (규칙 1).

```sql
-- open
UPDATE sale_event SET status = 'OPEN'
 WHERE id = ? AND status = 'SCHEDULED' AND opens_at <= NOW(3);

-- close
UPDATE sale_event SET status = 'CLOSED'
 WHERE id = ? AND status = 'OPEN';
```

- **`open()` 이 시각을 받지 않는다.** 도메인의 `open(Instant now)` 와 서명이 다르다.
  운영 판정은 DB 의 `NOW(3)` 이 한다 (규칙 7). 애플리케이션 시각을 넘기면 클럭 드리프트가
  판정에 섞이고, 시각을 조작한 요청이 아직 열리지 않은 회차를 열 수 있다.
  **불일치가 아니라 각 층이 답하는 질문이 다른 것이다.**
- **`close()` 에는 시각 조건이 없다.** `closes_at` 은 예정 시각이고 조기 마감은 정상 운영
  행위다 (2G-E-A §3). `status = 'OPEN'` 이 `SCHEDULED → CLOSED` 를 막는다.
- 경계는 `opens_at <= NOW(3)` — 도메인의 `now >= opensAt` 과 같다.

**동시성.** 2G-E-A §3 이 "도메인 객체는 동시 전이를 막지 않는다" 고 적은 그 지점을 여기서 막는다.
`CountDownLatch` 두 개(ready/start)로 16개 요청을 동시 출발시킨다 (`Thread.sleep` 없음, 규칙 25).

검증하는 계약은 경합 조합마다 다르다. **"항상 `APPLIED` 가 하나" 가 아니다.**

| 경합 | 최종 상태 | `APPLIED` 횟수 |
|---|---|---|
| open 끼리 | `OPEN` | **1** |
| close 끼리 | `CLOSED` | **1** |
| open + close 함께 | `OPEN` | **1** (`SCHEDULED → OPEN`) |
| | `CLOSED` | **2** (`SCHEDULED → OPEN`, `OPEN → CLOSED`) |

세 번째 경우에 **`SCHEDULED` 는 허용하지 않는다.** 마감은 `OPEN` 에서만 걸려 `SCHEDULED` 를
바꾸지 못하므로 오픈 요청 중 하나는 반드시 성공한다. 상태가 되돌아가는 전이가 없으니
오픈·마감은 각각 최대 한 번만 적용된다 — 그래서 최종 상태와 적용 횟수가 정확히 대응한다.

**실패가 조용히 통과하지 않게 한다.**

- 모든 작업 결과를 `Future.get()` 으로 회수한다. 작업 스레드에서 난 예외가
  `ExecutionException` 으로 테스트 스레드에 전파된다. 회수하지 않으면 "예외로 죽었다" 를
  "경합에서 졌다" 와 구분하지 못한 채 통과한다.
- `shutdown()` 후 제한 시간 안에 `awaitTermination` 이 성공하는지 단정한다.
- 결과 개수가 작업자 수와 같고 `null` 이 없는지 확인한다.

**시각 매핑.** `DATETIME(3)` → `Instant` 변환에 `getTimestamp()` 를 쓰지 않는다.
그 메서드는 값을 **JVM 기본 시간대**로 해석하므로 인스턴스마다 다른 `Instant` 가 된다.
`LocalDateTime` 으로 읽어 **UTC 로 고정**한다. 이 변환은 조회·표시용이며 판정에 쓰이지 않는다 —
오픈 여부는 SQL 안의 `opens_at <= NOW(3)` 이 정하고 그 비교는 DB 안에서 같은 타입끼리 이뤄진다.

### `JdbcTrainScheduleRepository`

```java
Optional<TrainSchedule> findById(TrainScheduleId id)
Optional<SaleEventId>  findSaleEventId(TrainScheduleId id)
```

**★ 재배정 API 를 제공하지 않는다.** `SaleEventId` 를 *입력으로* 받는 public 메서드가 없다.
테스트가 리플렉션으로 이 사실을 고정한다 — 나중에 누가 `reassign` 을 추가하면 깨진다.

`findSaleEventId` 가 실제로 조회한다는 사실 자체가 **`scheduleId` 를 `saleEventId` 로
대입하지 않는다**는 계약의 표현이다 (2G-D §8).

### `JdbcSaleEventScopeRepository`

```java
SaleEventId resolve(List<SeatId> seatIds)   // 정규화 조인, STRAIGHT_JOIN 고정
```

- 좌석이 없으면 / P-2 상한(4석)을 넘으면 / 중복이면 → `IllegalArgumentException`
- 요청 좌석이 전부 조회되지 않으면 → `SaleEventScopeException`
- 회차가 둘 이상이면 → `SaleEventScopeException`

**아무 회차나 고르는 대체 동작을 두지 않는다.** 근거 없이 하나를 고르면 그 사용자의 quota 가
무관한 회차에 합산된다 — 마이그레이션에서 금지한 것과 같은 종류의 오염이다.

**quota 상한을 검사하지 않는다.** 여기서 얻는 것은 "어느 카운터를 볼 것인가" 이고,
그 카운터에 조건부 UPDATE 를 거는 것은 `user_hold_quota` 의 몫이다 — **그 테이블은 아직 없다.**

### 포트 인터페이스를 만들지 않은 이유

구현이 하나뿐이고 소비할 애플리케이션 서비스가 없다. 지금 인터페이스를 만들면
**구현을 그대로 베낀 이름 하나**가 생길 뿐이다. 경계는 실제 유스케이스가 생길 때 긋는다.

---

## 8. 트리거를 넣지 않았다

B1 §11 이 "조건부 채택" 으로 남긴 것이다. **넣지 않았다.**

바이너리 로깅이 켜진 MySQL 에서 `CREATE TRIGGER` 는 `SUPER` 를 요구하므로
애플리케이션 계정으로 도는 Flyway 로는 만들 수 없다 (B1 §10 에서 실측).
마이그레이션 계정 분리가 선행돼야 하고 그것은 이 Task 의 범위가 아니다.

### 그래서 지금 소속 변경 방어는 이렇다

| 층 | 수단 | 상태 | 막는 것 | 못 막는 것 |
|---|---|---|---|---|
| 도메인 | 인스턴스 불변 + 재배정 API 부재 | ✅ 2G-E-A | 도메인 객체를 통한 변경 | 그 외 전부 |
| 저장소 | 재배정 UPDATE 미제공 | ✅ **이 Task** | 애플리케이션 정상 경로 | 직접 SQL, 운영 스크립트 |
| DB | `seat_inventory.schedule_id` FK | ✅ **이 Task** | 미매핑 좌석 INSERT, 좌석 있는 운행편 DELETE | **`sale_event_id` UPDATE** |
| DB | `BEFORE UPDATE` 트리거 | ❌ **미적용** | — | — |
| DB | 컬럼 권한 회수 | ❌ 보류 | — | — |

> **★ 도메인과 저장소의 정상 애플리케이션 경로에서는 소속 재배정을 차단하지만,
> DB 직접 SQL 수준의 `sale_event_id` 변경은 아직 차단하지 못한다.**
> 유효한 다른 회차로의 `UPDATE train_schedule SET sale_event_id=...` 는 그대로 통과하며,
> 테스트가 이 사실을 명시적으로 고정한다. 소속이 바뀌면 이미 쌓인 quota 의 의미가
> 달라진다 (2G-D §4). **알려진 위험이다.**

---

## 9. 테스트 픽스처 이행

`MySqlTestSupport.insertAvailableSeat(scheduleId, seatNo)` 가 좌석을 넣기 전에
운행편과 그 소속 회차를 보장한다. **변경을 이 헬퍼 한 곳에 모았으므로 각 테스트는 그대로다.**

```java
protected static void ensureTrainSchedule(long scheduleId) {
    // sale_event / train_schedule 을 ON DUPLICATE KEY UPDATE id = id 로 확보
}
```

- 소속은 고정된 `FIXTURE_SALE_EVENT_ID` **하나**로만 간다.
  `scheduleId` 를 `saleEventId` 로 대입하지 않는다.
- `ON DUPLICATE KEY UPDATE id = id` 는 값을 바꾸지 않는 no-op 이다.
  회차별 동작을 검증하는 테스트가 직접 넣은 매핑을 **덮어쓰지 않는다.**
- `resetSeatInventory` 가 FK 순서에 맞춰 좌석 → 운행편 → 회차 순으로 지운다.

**운영 코드는 이런 자동 생성을 하지 않는다.** 픽스처가 하는 일은 "테스트가 쓰겠다고 선언한
운행편을 준비" 하는 것이지 매핑을 **추측**하는 것이 아니다.

---

## 10. 검증 결과

`./gradlew :modules:reservation-infra:test --rerun-tasks` — **371 tests 통과.**
`./gradlew check` (clean) 통과. 실제 MySQL 8.4 Testcontainers, H2 없음 (규칙 26).

| | 수 |
|---|---|
| 기존 테스트 | **321** (전부 유지) |
| `SaleEventSchemaMigrationTest` | 13 |
| `JdbcSaleEventRepositoryTest` | 17 |
| `JdbcSaleEventScopeRepositoryTest` | 20 |
| **합계** | **371** |

동시 전이 테스트 3개의 실행 시간은 이 환경에서 약 **0.063초**였고, 인프라 테스트 전체는
약 2분에서 약 **64초**로 줄었다. 경합이 끝나기를 기다리는 방식을 고친 결과다 —
이전에는 `shutdown()` 없이 `awaitTermination` 을 기다려 상한을 그대로 소진했다.

> **실행 시간은 환경에 따라 달라진다.** 성능 보장이 아니라, 잘못된 대기가 사라졌다는
> 정황으로만 적는다 (규칙 30·31).

### 검증한 것

- 빈 스키마에서 V1~V5 가 끝까지 적용된다
- `sale_event.status` 가 **대소문자를 구분한다**
- `train_schedule` 이 존재하지 않는 회차를 참조할 수 없다
- `seat_inventory` 에 `sale_event_id` 컬럼이 **없다**
- V4 가 기존 좌석 8행을 건드리지 않고 테이블만 만든다
- **매핑 없이 V5 를 적용하면 실패하고 좌석이 보존된다** (FK 도 붙지 않는다)
- 실패 원인에서 미매핑 운행편임을 알 수 있다
- **`repair` 없이는 재개되지 않고, `repair` 후에는 같은 파일이 적용된다**
- FK 적용 후 미매핑 좌석 INSERT 거부, 좌석 있는 운행편 DELETE 거부
- **유효한 다른 회차로의 소속 UPDATE 는 여전히 통과한다** (남은 위험을 고정)
- 회차 조회·복원, 미래 오픈 시각의 `OPEN` 행도 그대로 복원
- 오픈/마감 조건부 UPDATE 의 여섯 경우
- **동시 전이의 적용 횟수가 최종 상태와 대응한다** (`CountDownLatch`, 16 workers)
  — open 끼리 / close 끼리는 각각 1회, open+close 는 최종 `OPEN` 이면 1회 · `CLOSED` 면 2회,
  `SCHEDULED` 로 끝나지 않는다
- **경합 작업의 예외가 `Future.get()` 으로 전파되고 풀 종료가 제한 시간 안에 검증된다**
- 운영 테이블에서 **구동 테이블이 `seat_inventory`, `ts` 가 `eq_ref`, 양쪽 `PRIMARY`**
- **통계 미갱신 상태에서 읽는 행 수 5행** (미고정 608행 대비)
- 좌석 4행 → 600행 에서 읽는 행 수가 늘지 않는다
- 회차 교차 요청, 없는 좌석, 중복 좌석, 상한 초과 거부
- 저장소에 `SaleEventId` 를 입력으로 받는 인스턴스 메서드가 없다

### 검증하지 않은 것

- **애플리케이션 서비스·REST API** — 만들지 않았다
- **`user_hold_quota` 와의 연결** — 테이블이 없다. **I-12 는 여전히 운영 미구현이다**
- **회차 상태와 선점 경로의 연결** — `isOpen()` 을 실제 좌석 선점이 검사하도록 배선하지 않았다
- **동시 부하에서의 조인 비용** — 단일 세션 실행 계획만 봤다
- **실제 운영 데이터 규모** — 좌석 600행이 상한이었다
- **DB 서버 시간대가 UTC 가 아닌 환경** — 표시 값이 어긋난다 (§7)
- **트리거·권한 분리** — 적용하지 않았다
- 2G-C·2G-D·2G-E-A·2G-E-B1 이 남긴 미검증 항목은 그대로다

---

## 11. 남은 위험

| 항목 | 내용 |
|---|---|
| **I-12 는 여전히 운영 미구현** | quota 범위를 *가리키는* 모델과 조회 경로가 생겼을 뿐이다. `user_hold_quota` 테이블·카운터 조건부 UPDATE·세 이탈 경로 연동이 없다 |
| **★ DB 직접 SQL 의 소속 변경** | 도메인과 저장소는 정상 경로를 막지만, `UPDATE train_schedule SET sale_event_id=...` 는 통과한다. 트리거는 계정 분리가 전제라 미적용 (§8) |
| **`DELETE` 후 재삽입** | 좌석이 붙어 있으면 FK 가 막지만, 좌석 없는 운행편은 여전히 열려 있다 |
| **`STRAIGHT_JOIN` 은 계획을 고정할 뿐 비용을 없애지 않는다** | 요청당 최대 7행을 읽는다. 비정규화 대비 손해는 그대로다 |
| **`STRAIGHT_JOIN` 의 경직성** | 나중에 조건이 늘어 다른 순서가 유리해져도 옵티마이저가 바꾸지 못한다. 조건을 바꿀 때 EXPLAIN 어서션을 함께 봐야 한다 |
| **회차 상태가 선점을 막지 않는다** | `CLOSED` 회차의 좌석도 지금은 선점된다. 배선이 없다 |
| **`DATETIME(3)` 의 시간대** | UTC 로 고정해 결정적이지만, DB 서버가 UTC 가 아니면 표시 값이 어긋난다. 근본 해결은 `TIMESTAMP` 저장이나 세션 시간대 고정이며 `seat_inventory` 전체에 영향을 준다 |
| **운영자 매핑 입력 절차가 문서뿐** | 등록 API 도 검증 도구도 없다. 사람이 SQL 로 넣는다 |
| **회차 생성·수정 경로 없음** | 저장소는 조회와 전이만 한다 |
| **자동 마감 주체 없음** | `closes_at` 이 지나도 아무도 닫지 않는다 (2G-E-A §8 그대로) |
| **오픈 전 취소 경로 없음** | `SCHEDULED → CLOSED` 를 막았으므로 잘못 등록된 회차는 오픈 시각까지 남는다 (2G-E-A §7) |

---

## 12. 후속 Task

1. **Task 2G-F — 만료 배치 운영 계약** (2G-C §10 의 8가지)
2. **Task 2G-G — `user_hold_quota` 마이그레이션과 운영 저장소**
   - 이제 FK 대상(`sale_event`)이 존재한다. B1 §11 이 막혀 있던 이유가 해소됐다
   - PK `(sale_event_id, user_id)`, 카운터 조건부 UPDATE
3. **Task 2H — 애플리케이션 서비스와 트랜잭션 경계**
   - `resolve` → quota 확보 → 좌석 선점의 순서 배선 (2G-B 의 quota → seat 잠금 순서)
   - `SaleEvent.isOpen()` 을 선점 경로가 검사하도록 연결
   - 예외의 HTTP 매핑 (`SaleEventScopeException`, `SaleEventStateTransitionException`)
4. **마이그레이션 계정 분리** — 결정되면 트리거 적용을 재검토한다
5. **프론트엔드** — [FRONTEND.md](../FRONTEND.md) 의 목표 설계. 이 Task 범위 밖이다

---

## 13. 재현 명령

```bash
./gradlew :modules:reservation-infra:test \
  --tests "*SaleEventSchemaMigrationTest" \
  --tests "*JdbcSaleEventRepositoryTest" \
  --tests "*JdbcSaleEventScopeRepositoryTest"
```

Docker 가 실행 중이어야 한다. 마이그레이션 절차 테스트는 공유 컨테이너에 별도 스키마
(`railgate_mig_2geb2`)를 만들어 처음부터 돌리고 끝나면 지운다.
