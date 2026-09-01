# 결정 — SaleEvent 운영 스키마 (Task 2G-E-B1)

> TASK-002G-E-B1 · 2026-08-30
> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.6·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [CLAUDE.md](../../CLAUDE.md) 규칙 26·27·30
> 선행: [2G-D](TASK-002G-D-quota-scope-contract.md) · [2G-E-A](TASK-002G-E-A-sale-event-domain.md)

## 1. 문제 정의

[2G-E-A](TASK-002G-E-A-sale-event-domain.md) 는 `SaleEvent`·`TrainSchedule` 을 순수 도메인으로
만들었지만, 운영 DB 로 옮기기 전에 **되돌리기 어려운 결정 두 개**가 남아 있다.

| 미결정 | 출처 |
|---|---|
| `seat_inventory` 에 `sale_event_id` 를 비정규화할 것인가 | 2G-D §5 |
| 같은 `TrainScheduleId` 의 영속 소속 변경을 DB 에서 어떻게 막을 것인가 | 2G-E-A §4 세 번째 줄 |

여기에 실무적 제약이 하나 더 있다. **기존 `seat_inventory.schedule_id` 에는 FK 가 없고
참조 대상도 없다**(V1 주석). 기존 테스트는 임의의 `scheduleId`(101, 999 …)로 좌석을 넣는다.
FK 를 바로 걸면 그 데이터가 전부 orphan 이 된다.

**이 Task 는 결정 실험이다.** V4 마이그레이션·운영 저장소·Spring 배선·애플리케이션 서비스·
REST API·`user_hold_quota` 운영 구현을 만들지 않는다. V1/V2/V3 도 수정하지 않는다.

---

## 2. 기존 스키마와 제약

| 사실 | 확인 위치 |
|---|---|
| `seat_inventory` 에는 `schedule_id` 만 있다 | `V1__seat_inventory.sql` |
| `schedule_id` 는 **참조 대상 없는 값**이다 | V1 주석 "train_schedule 테이블은 후속 Task 에서 추가" |
| 운영 `sale_event`·`train_schedule` 테이블이 **없다** | `db/migration` 에 V1·V2·V3 뿐 |
| `SaleEvent`·`TrainSchedule` 은 **순수 도메인에만** 있다 | 2G-E-A |
| I-12 quota 운영 구현이 **없다** | `user_hold_quota` 테이블 부재 |
| 기존 테스트는 임의의 `scheduleId` 로 좌석을 넣는다 | `MySqlTestSupport.insertAvailableSeat` |

### 테스트 전용 테이블

운영 테이블을 만들거나 ALTER 하지 않았다. 같은 컨테이너를 공유하는 다른 테스트의 스키마를
건드리면 그 테스트들이 검증하는 것이 달라진다. 그래서 좌석 테이블도 **실험용 사본**을 썼다.

```
task2geb1_sale_event         task2geb1_ms_sale_event      task2geb1_mig_sale_event
task2geb1_train_schedule     task2geb1_ms_train_schedule  task2geb1_mig_train_schedule
task2geb1_seat_normalized    task2geb1_ms_association     task2geb1_mig_seat
task2geb1_seat_denormalized
```

각 테스트가 `@BeforeEach` 에서 만들고 `@AfterAll` 에서 지운다. `Task2gQuotaCounter`(2G-A) 와
같은 방식이다.

---

## 3. 실험 가설

| # | 가설 | 결과 |
|---|---|---|
| H1 | 정규화 조인 비용은 **요청 좌석 수**에 갇히고 전체 크기와 무관하다 | ✅ 성립 |
| H2 | 그렇다면 비정규화의 근거가 없다 | ✅ 정규화 채택 |
| H3 | FK 는 소속 불변을 보장한다 | ❌ **반증** (§9) |
| H4 | 트리거는 소속 불변을 완전히 보장한다 | ❌ **부분만** (§10) |
| H5 | 기존 데이터에 FK 를 바로 걸 수 있다 | ❌ **반증** (§12) |

---

## 4. 테스트 데이터 분포

| | 작은 규모 | 큰 규모 |
|---|---|---|
| 판매 회차 | 3 | 3 |
| 운행편 | 20 | **2,000** |
| 운행편당 좌석 | 20 | 30 |
| **총 좌석** | **400** | **60,000** (150배) |

**규모를 이렇게 고른 이유.** "전체 크기가 커져도 대상 스캔이 제한되는가" 를 보려면 두 자릿수
배수가 필요하고(150배), 동시에 CI 에서 한 번의 `INSERT ... SELECT` 로 만들 수 있어야 한다.
판단 근거는 절대 수치가 아니라 **두 규모 사이의 차이**이므로, 더 키워도 결론이 달라지지 않는다면
키울 이유가 없다.

**분포를 한쪽에 유리하게 두지 않았다.** 4석 요청을 두 형태로 잰다.

- **같은 운행편 4석** — 가장 흔한 요청. 조인 대상이 한 행이라 정규화에 유리하다
- **다른 운행편 4석** — 왕복·다구간 요청. 조인 대상이 네 행으로 흩어져 정규화에 불리하다

운행편의 회차 배정은 `1 + (scheduleId % 3)` 으로, 한 회차에 몰거나 흩뜨리지 않았다.

**두 대안에 같은 조건을 줬다.** 양쪽 좌석 테이블은 `seat_inventory`(V1) 와 같은
`PRIMARY KEY (id)` · `UNIQUE (schedule_id, seat_no)` 를 갖고, **비정규화 쪽에는
`sale_event_id` 인덱스를 추가로** 줬다.

---

## 5. 비교한 SQL

테스트가 EXPLAIN 하는 문자열과 아래 SQL 은 **같은 상수**다 (`Task2geb1Schema`).
복사본이 조용히 어긋나는 것을 막는다.

`DISTINCT` 인 이유: 선점 경로가 알아야 하는 것은 "이 좌석들의 판매 회차" 이고, 회차가 둘 이상이면
**요청 자체를 거부**해야 한다 (2G-D §4: 이벤트 간 quota 는 독립). 행 수가 1 이 아니면 교차 요청이다.

**Q1 — `scheduleId` 를 아는 요청 (두 대안 동일)**

```sql
SELECT sale_event_id FROM train_schedule WHERE id = ?;
```

**Q2·Q3 — 정규화**

> 아래는 **이 실험이 실행한 SQL** 이다. 최종 구현은 다르다 — `DISTINCT` 를 빼고
> `STRAIGHT_JOIN` 을 넣었다 ([2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §5). 이유는 각각 누락 좌석 탐지와 조인 순서 고정이다.

```sql
SELECT DISTINCT ts.sale_event_id
  FROM seat_inventory s
  JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.id IN (?, ?, ?, ?);
```

**Q2·Q3 — 비정규화**

```sql
SELECT DISTINCT sale_event_id
  FROM seat_inventory
 WHERE id IN (?, ?, ?, ?);
```

**비정규화를 택했을 때 추가로 필요한 것 — drift 검사**

```sql
SELECT s.id, s.sale_event_id, ts.sale_event_id
  FROM seat_inventory s
  JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.sale_event_id <> ts.sale_event_id;
```

---

## 6. EXPLAIN 결과 (좌석 60,000행)

| 조회 | 대안 | table | type | key | rows | Extra |
|---|---|---|---|---|---|---|
| Q1 | 공통 | `train_schedule` | `const` | PRIMARY | 1 | — |
| 1석 | 정규화 | `s` / `ts` | `const` / `const` | PRIMARY / PRIMARY | 1 / 1 | — |
| 1석 | 비정규화 | 좌석 | `const` | PRIMARY | 1 | — |
| 4석 | 정규화 | `s` | `range` | PRIMARY | 4 | Using where; Using temporary |
| | | `ts` | **`eq_ref`** | PRIMARY | 1 | — |
| 4석 | 비정규화 | 좌석 | `range` | PRIMARY | 4 | Using where; Using temporary |

- **풀 스캔(`ALL`) 없음**, **filesort 없음** — 양쪽 모두
- `Using temporary` 는 `DISTINCT` 때문이며 **양쪽에 똑같이** 있다. 4행짜리 임시 테이블이다
- 1석은 MySQL 이 최적화 시점에 상수화한다 (`Rows fetched before execution`)
- 조인 쪽은 `eq_ref` — 좌석 한 건당 운행편 PK 한 건. **범위 스캔이 아니다**

---

## 7. 실제로 읽은 행 수와 증가 복잡도

`EXPLAIN` 의 `rows` 는 옵티마이저의 **추정**이다. 판단 근거는 스토리지 엔진이 실제로 넘긴
행 수(`Handler_read_*` 세션 카운터)로 잰다. 하드웨어와 무관한 정수다.

> **★ 중요한 단서 ([2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §5).** 아래 측정은 픽스처가 `ANALYZE TABLE` 을 돌린
> 직후의 값이다. **통계가 최신이 아니면 옵티마이저가 조인 순서를 뒤집어** 읽는 행 수가
> 전체 좌석 수에 비례하게 된다 (좌석 600행에서 520~609행 관측).
> 즉 아래 수치는 **조인 순서를 고정했을 때만** 유지된다. 운영 SQL 은 `STRAIGHT_JOIN` 을 쓴다.

### 좌석 60,000행 실측

| 조회 | 정규화 | 비정규화 | 차이 |
|---|---|---|---|
| Q1 (scheduleId 앎) | 1 | 1 | 0 |
| 1석 | **2** | **1** | +1 |
| 4석 (같은 운행편) | **7** | **6** | +1 |
| 4석 (다른 운행편) | **12** | **6** | +6 |

### 규모를 150배로 키웠을 때

| 조회 | 좌석 400행 → 60,000행 |
|---|---|
| 정규화 1석 / 4석(같은) / 4석(다른) | **늘지 않음** |
| 비정규화 1석 / 4석 | **늘지 않음** |

> 두 규모의 값이 **"같다" 고 단정하지 않았다.** 400행짜리 표에서는 전체를 훑는 편이 실제로
> 더 싸서 옵티마이저가 그렇게 고를 수 있다. 확인한 것은 **커졌을 때 나빠지지 않는가** 이고,
> 그것을 `large <= small` 과 `large <= 16` 두 조건으로 고정했다.

### 읽는 것

- 정규화의 최악(4석·서로 다른 운행편)이 **12행**이다. 비정규화의 **2배**다
- 그 계수는 **요청 좌석 수**(P-2: 최대 4)에 매여 있고 **전체 크기와 무관**하다
- 좌석이 150배가 돼도 두 대안 모두 읽는 행 수가 늘지 않는다

**실행 시간은 기록하지 않는다.** 규칙 31 이 금지하는 오독을 피하기 위해서다.
같은 이유로 "조인 비용이 0" 이라고 쓰지도 않는다 — 정규화는 실제로 **최대 2배를 더 읽는다.**

---

## 8. 결정 1 — 저장 구조: **정규화 채택**

`seat_inventory` 에 `sale_event_id` 를 **넣지 않는다.**

### 근거

비정규화를 택하려면 세 조건이 필요했다.

| 조건 | 결과 |
|---|---|
| 정규화 경로의 비용이 데이터 증가에 따라 의미 있게 악화됨 | ❌ **150배에서 변화 없음** |
| PK/FK/index 로도 해결되지 않음 | ❌ 조인 쪽이 이미 `eq_ref` PRIMARY 다 |
| drift 방지 비용보다 읽기 이점이 명확함 | ❌ 이점은 요청당 최대 6행, 대가는 영구적인 drift 감시 |

**조인이 하나 더 있다는 것만으로는 근거가 되지 않는다** — 그 조인의 비용이 요청 크기에
갇혀 있기 때문이다.

### 포기하는 것

- 요청당 최대 6행을 더 읽는다 (4석·서로 다른 운행편 기준 12 대 6)
- 좌석에서 출발하는 모든 경로에 조인이 하나 붙는다
- 나중에 이 비용이 실측으로 문제가 되면 **그때 비정규화를 다시 검토**한다.
  측정 없이 미리 하지 않는다

### 비정규화가 만들었을 것 (실측)

운행편의 소속만 바꾸고 좌석 행을 그대로 두면 **좌석 20행이 운행편과 다른 회차를 가리킨다.**
같은 조작을 정규화 구조에서 하면 조회는 항상 현재 값을 준다 — 어긋날 값 자체가 없다.

---

## 9. ★ FK 만으로 소속 불변을 막지 못한다 (실패 재현)

```text
FK 가 있으니 소속도 불변일 것이다
  → 실제로는 다른 유효한 SaleEventId 로 UPDATE 가 성공한다
```

| 시도 | 결과 |
|---|---|
| 존재하지 않는 `saleEventId` 로 INSERT | **거부** (FK) |
| 존재하지 않는 `saleEventId` 로 UPDATE | **거부** (FK) |
| **존재하는 다른 `saleEventId` 로 UPDATE** | **성공** ★ |

추석 회차의 운행편이 설 회차로 옮겨간다. FK 는 아무 말도 하지 않는다.
그 운행편의 좌석에 쌓인 quota 는 이제 어느 회차의 것인지 되짚을 수 없다.

> **FK 는 "유효한 SaleEvent 를 참조한다" 만 보장한다.**
> **"처음 참조한 그 SaleEvent 를 계속 참조한다" 는 다른 규칙이다.**

---

## 10. 영속 소속 변경 방어 — 네 대안 비교

### 대안 1 — 저장소에 UPDATE API 를 만들지 않음

2G-E-A 가 도메인에서 이미 한 것과 같은 층이다. **애플리케이션 정상 경로만** 막는다.
직접 SQL·운영 스크립트·다른 저장소·잘못된 마이그레이션은 막지 못한다.

> "repository 에 메서드가 없다" 를 DB 수준 보장이라고 부르지 않는다.

### 대안 2 — `BEFORE UPDATE` 트리거

```sql
CREATE TRIGGER trg_train_schedule_membership
BEFORE UPDATE ON train_schedule
FOR EACH ROW
BEGIN
    IF NEW.sale_event_id <> OLD.sale_event_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'I-12-SCOPE: train_schedule.sale_event_id is immutable';
    END IF;
END
```

**동작 (실측)**

| 시도 | 결과 |
|---|---|
| 다른 회차로 재배정 | **거부**, 값 유지 |
| 같은 회차로 UPDATE (멱등) | **허용** |
| `sale_event_id` 와 무관한 컬럼 UPDATE | **허용** |
| 새 운행편 INSERT | **영향 없음** |
| 예외 메시지 | `I-12-SCOPE` 표지로 **식별 가능** |

**막지 못하는 것 (실측)**

| 경로 | 관측 |
|---|---|
| `DELETE` 후 재삽입 | **통과한다.** `BEFORE UPDATE` 는 UPDATE 만 본다 |
| `DROP TRIGGER` 할 수 있는 계정 | **방어가 멈춘다** |

**★ 이번 실험에서 새로 드러난 대가**

바이너리 로깅이 켜진 MySQL 에서 `CREATE TRIGGER` 는 `SUPER`(또는 `SET_USER_ID`)를 요구한다.

```
You do not have the SUPER privilege and binary logging is enabled
```

즉 **애플리케이션 계정으로는 이 트리거를 만들 수 없다.**
현재 `MySqlTestSupport` 는 Flyway 를 애플리케이션 계정으로 돌린다.
이 방어를 택하면 **마이그레이션 계정 분리가 선택이 아니라 전제**가 된다.

더 나쁜 비대칭도 관측했다. `DROP TRIGGER` 는 `TRIGGER` 권한만 요구한다.

> 애플리케이션 계정은 트리거를 **지울 수는 있고 되살릴 수는 없다.**
> 방어를 한 번 걷어내면 애플리케이션 계정으로는 복구되지 않는다.

**나머지 단점**

- 숨은 DB 로직 — 코드를 읽어서는 이 규칙의 존재를 알 수 없다
- 정당한 데이터 정정에 명시적 절차가 필요하다 (DROP → 정정 → 재생성, 모두 관리 계정)
- Flyway 로 트리거를 관리하는 부담 — 재생성 순서와 idempotency

### 대안 3 — 별도 연결 테이블 (append-only 의도) — **기각**

`UNIQUE (schedule_id)` 로 "운행편당 소속 한 개" 를 강제하면 불변이 될 것 같지만 **아니다.**

| 시도 | 결과 |
|---|---|
| 같은 운행편에 두 번째 소속 INSERT | **거부** |
| **기존 연결 행 UPDATE** | **성공** ★ |
| DELETE 후 재삽입 | **성공** ★ |

`UNIQUE` 는 "한 개" 만 강제하고 "바뀌지 않음" 은 강제하지 않는다.
**문제를 한 테이블 옆으로 옮길 뿐**이고, 조회에는 조인이 하나 더 붙는다.
결국 여기에도 트리거가 필요하므로 대안 2 대비 얻는 것이 없다.

### 대안 4 — 컬럼 단위 UPDATE 권한 회수

```sql
GRANT SELECT, INSERT, DELETE ON railgate.train_schedule TO 'app'@'%';
GRANT UPDATE (id, note_seq) ON railgate.train_schedule TO 'app'@'%';  -- sale_event_id 제외
```

**동작 (실측)**

| 시도 | 결과 |
|---|---|
| 제한된 계정이 `sale_event_id` UPDATE | **거부** (`command denied`) |
| 제한된 계정이 다른 컬럼 UPDATE | **허용** |
| 제한된 계정이 DELETE 후 재삽입 | **통과한다** ★ |

숨은 DB 로직이 없고 우회할 수 없다는 점에서 가장 강하다. 다만 **계정을 만들 권한이 필요하고**
(실험에서는 컨테이너 root 를 썼다), INSERT/DELETE 권한이 남아 있으면 여전히 우회된다.
선점 경로가 운행편을 INSERT 하지 않는다면 그 권한도 뺄 수 있지만, 그 판단은 저장소 계약이
정해진 뒤에야 가능하다.

---

## 11. 결정 2 — 선택한 방어 수단과 포기하는 것

**세 층을 구분해서 적는다.** 어느 것도 "소속 변경을 막는다" 로 뭉뚱그리지 않는다.

| 층 | 수단 | 상태 | 막는 것 | 못 막는 것 |
|---|---|---|---|---|
| 도메인 | 인스턴스 불변 + 재배정 API 부재 | ✅ 2G-E-A 완료 | 도메인 객체를 통한 변경 | 그 외 전부 |
| 저장소 | 재배정 UPDATE 를 제공하지 않음 | 2G-E-B2 | 애플리케이션 정상 경로 | 직접 SQL, 운영 스크립트 |
| DB (a) | `seat_inventory.schedule_id` FK | **채택 · 2G-E-B2** | 좌석이 있는 운행편의 **DELETE**, 미매핑 좌석 INSERT | `sale_event_id` UPDATE |
| DB (b) | `BEFORE UPDATE` 트리거 | **조건부 채택** | `sale_event_id` **UPDATE** | DELETE 후 재삽입, DROP TRIGGER |
| DB (c) | 컬럼 권한 회수 | **보류** | 위 모두 + 트리거 삭제 | DELETE 후 재삽입 |

### (a) FK 는 무조건 채택한다

대가가 없고, **실험 B 가 트리거의 구멍으로 지목한 "DELETE 후 재삽입" 을 이 FK 가 좁힌다**
— 좌석이 붙은 운행편은 지워지지 않는다 (§12 에서 실측). 층이 서로의 구멍을 메우는 형태다.

### (b) 트리거는 **계정 분리 결정과 함께** 판단한다

동작과 대가를 확인했지만 **이번 Task 에서 운영 마이그레이션에 넣지 않는다.**
적용하려면 Flyway 를 관리 계정으로 돌리는 변경이 선행돼야 하고, 그것은 이 Task 의 범위가 아니다.
그 변경 없이는 **적용 자체가 불가능하다는 것**이 이번 실험의 결과다.

### (c) 권한 회수는 보류한다

가장 강하지만 마이그레이션 계정과 애플리케이션 계정 분리, GRANT 운영, 로컬 개발 환경의
동일 재현까지 딸려온다. 현재 프로젝트 범위 밖이다. **동작은 확인해 뒀다.**

### 운영자의 정정 경로

소속 정정이 정말 필요한 경우는 **관리 계정으로 트리거를 DROP → 정정 → 재생성**한다.
절차가 명시적이라 감사 로그에 남는다. 이것을 "우회" 가 아니라 **설계된 경로**로 문서화한다.

> **트리거를 넣어도 운영 복구와 직접 SQL 의 위험이 사라지지 않는다.**
> 좁아질 뿐이고, 어디까지 좁아지는지는 위 표가 전부다.

---

## 12. migration 호환성과 backfill 순서

### 세 선택지 비교 (실측)

| | 선택지 A (단계 분리) | 선택지 B (FK 연기) | 선택지 C (한 번에) |
|---|---|---|---|
| 기존 데이터가 있을 때 | ✅ 통과 | ✅ 통과 | ❌ **실패** |
| 새 orphan 유입 | 차단 | ❌ **계속 생긴다** | 차단 |
| 롤백·재실행 | ✅ 가능 | — | ✅ 가능 (실패해도 무손상) |
| 테스트 fixture 변경량 | 중간 | 없음 | 큼 |

**선택지 C 는 실패한다.** 매핑되지 않은 운행편이 하나라도 있으면 `ADD CONSTRAINT` 가
`DataIntegrityViolationException` 으로 끝난다. 다만 **실패해도 좌석 행은 그대로이고 제약도
붙지 않는다** — 원자적이라 orphan 을 정리한 뒤 같은 마이그레이션을 다시 돌릴 수 있다.

**선택지 B 는 문제를 남긴다.** FK 가 없으면 정리한 뒤에도 저장소를 우회하는 경로
(운영 스크립트·배치·다른 저장소)로 새 orphan 이 계속 들어온다. 실측으로 재현했다.

### ✅ 채택 — 선택지 A

**당시(2G-E-B1) 계획한 형태**

```
V4  sale_event · train_schedule 생성
V5  backfill — 운영자가 등록한 명시적 매핑만 넣는다
V6  orphan 탐지 → 0행 확인 → seat_inventory.schedule_id FK 추가
```

> **★ 정정 — 실제 구현은 이 형태가 아니다 ([2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §4).**
>
> 구현하면서 **backfill 을 마이그레이션 파일로 만들 수 없다**는 것이 드러났다.
> "이 운행편이 어느 판매 회차에 속하는가" 의 답을 저장소가 갖고 있지 않으므로, 파일이 답을
> 가지려면 지어내는 수밖에 없고 그것은 아래 §12 가 금지한 지름길과 같다.
> 그래서 backfill 은 **마이그레이션이 아니라 V4 와 FK 사이의 명시적 운영 단계**가 됐고,
> **V6 은 만들어지지 않았다.**
>
> **최종 구현**
>
> ```
> V4                sale_event · train_schedule 생성                 (자동)
> ── 운영 단계 ──    운영자가 근거 있는 매핑을 입력                     (수동)
> V5                orphan 0 검증 → seat_inventory.schedule_id FK 추가 (자동)
> ```
>
> 빈 스키마에서는 `flyway migrate` 가 V4 다음 V5 로 그대로 이어진다(좌석이 없어 orphan 도 없다).
> **기존 좌석 데이터가 있는 배포에서만** `flyway migrate -target=4` 로 멈춰 매핑을 넣고
> `flyway migrate` 로 재개한다. 실수로 V5 를 먼저 돌려 실패 이력이 남았다면
> 원인을 없앤 뒤 `flyway repair` 가 필요하다.

**orphan 탐지 쿼리**

```sql
SELECT DISTINCT s.schedule_id
  FROM seat_inventory s
  LEFT JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE ts.id IS NULL;
```

### 실패 정책

**orphan 이 0행이 아니면 마이그레이션을 실패시킨다.** 조용히 넘어가지 않는다.
FK 추가가 그 자체로 실패하므로 별도 검사 없이도 실패하지만, **왜 실패했는지를 알 수 있도록**
FK 마이그레이션(구현에서는 V5)은 탐지를 먼저 수행한다.

### ★ 금지 — 세 지름길은 전부 "통과해버린다"

아래 세 가지는 **FK 를 만족시키고 마이그레이션을 초록색으로 만든다.** 그래서 위험하다.

| 지름길 | 관측된 결과 |
|---|---|
| 미매핑 운행편을 **기본 회차에 몰아넣기** | FK 통과. 그 좌석은 무관한 회차의 quota 에 합산된다. **데이터가 정합적으로 보여 탐지할 수 없다** |
| `scheduleId` 를 `saleEventId` 로 **그대로 대입** | FK 통과. 운행편 수만큼 회차가 생겨 **2G-D §2 가 기각한 운행편 단위 범위와 같아진다** |
| orphan 좌석 **조용히 삭제** | FK 통과. 좌석 4행이 사라진 것을 아무도 모른다 |

세 경우 모두 테스트로 재현해 뒀다. **통과한다는 것이 옳다는 뜻이 아니다** (규칙 27).

### FK 적용 후 확인된 효과

| 시도 | 결과 |
|---|---|
| 미매핑 운행편의 좌석 INSERT | **거부** |
| 좌석이 있는 운행편 DELETE | **거부** ← 트리거의 구멍을 좁힌다 (§11) |

### 기존 테스트 fixture

`MySqlTestSupport.insertAvailableSeat(scheduleId, seatNo)` 가 임의의 `scheduleId` 를 쓴다.
FK 적용 이후에는 그 운행편이 존재해야 한다. **fixture 변경을 한 곳에 모으는 것**이 2G-E-B2 의
과제다 — 헬퍼가 운행편 행을 먼저 보장하게 하면 각 테스트는 그대로 둘 수 있다.
*(→ [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §9 에서 그렇게 적용했다. 기존 321개 테스트는 수정 없이 통과한다)*

---

## 13. 검증한 것과 검증하지 않은 것

`./gradlew :modules:reservation-infra:test` — 실제 MySQL 8.4 Testcontainers, **45 tests 통과.**

| 테스트 | 수 |
|---|---|
| `SaleEventScopeLookupPlanTest` | 17 |
| `TrainScheduleMembershipImmutabilityExperimentTest` | 18 |
| `SaleEventMigrationCompatibilityExperimentTest` | 10 |

### 검증한 것

- 정규화 PK 조회·조인, 비정규화 직접 조회의 **실행 계획과 실제 읽은 행 수**
- 1석과 4석, 그리고 **같은 운행편 / 다른 운행편** 두 분포
- 좌석 **400행 → 60,000행** 에서 읽는 행 수가 늘지 않음
- 두 대안이 **같은 SaleEventId** 를 반환하고, 교차 요청을 같은 방식으로 드러냄
- 비정규화의 **drift 재현**
- FK 가 **존재하지 않는 회차 참조는 거부**하고 **유효한 회차 간 재배정은 거부하지 못함**
- 트리거의 거부·멱등 UPDATE·무관 컬럼 UPDATE·메시지 식별
- 트리거의 **한계 3종** (DELETE 후 재삽입 / DROP TRIGGER / **애플리케이션 계정으로 생성 불가**)
- 연결 테이블이 **기존 행 UPDATE 를 막지 못함**
- 컬럼 권한 회수의 **동작과 잔여 우회**
- orphan 탐지 쿼리
- 선택지 C 실패와 **무손상 재실행 가능성**
- 금지된 지름길 3종이 **FK 를 통과해버림**

### 검증하지 않은 것

- ~~**운영 마이그레이션 자체**~~ → [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) 에서 V4·V5 로 적용했다 (V6 은 만들지 않았다)
- ~~**운영 저장소**~~ → [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) 에서 조회·전이·범위 해석 저장소를 만들었다.
  **애플리케이션 서비스와 API 는 여전히 없다**
- ~~**실제 `seat_inventory` 에서의 실행 계획**~~ → [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §5 에서 확인했고,
  **조인 순서를 고정해야 한다는 사실이 그때 드러났다**
- **동시 부하에서의 조인 비용** — 단일 세션 실행 계획만 봤다
- **트리거가 대량 UPDATE·복제에 주는 영향**
- **마이그레이션 계정 분리의 운영 절차** — 동작만 확인했다
- **`user_hold_quota` 와의 실제 연결** — 여전히 테스트 전용이다
- 2G-C·2G-D·2G-E-A 가 남긴 미검증 항목은 **그대로 남아 있다**

---

## 14. 남은 위험

| 항목 | 내용 |
|---|---|
| **I-12 는 여전히 운영에서 강제되지 않는다** | **B1 종료 당시** — 이 Task 는 스키마를 결정했을 뿐이고 테이블·저장소·서비스가 없었다.<br>**[2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) 완료 후 현재** — `sale_event`·`train_schedule`(V4)과 `seat_inventory.schedule_id` FK(V5), 회차·운행편 조회 저장소와 quota 범위 해석 저장소가 구현됐다. 그러나 `user_hold_quota` 테이블, quota 카운터 조건부 UPDATE, 확정·만료·해제 이탈 경로 연동, 애플리케이션 서비스 배선이 없다. **즉 quota 범위를 표현하고 조회하는 스키마와 저장소는 구현됐지만 I-12 자체는 아직 운영에서 강제되지 않는다.** |
| **DB 직접 SQL 의 소속 변경** | *(→ [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) 에서 도메인·저장소·FK 세 층을 적용했다. **정상 애플리케이션 경로는 차단되지만 DB 직접 SQL 수준의 `sale_event_id` 변경은 여전히 차단하지 못한다** — 트리거 미적용)* |
| **트리거 적용이 계정 분리에 묶여 있다** | 애플리케이션 계정으로는 생성할 수 없다. 계정 분리를 하지 않으면 (b) 층은 비어 있게 된다 |
| **DELETE 후 재삽입은 어느 층도 완전히 막지 못한다** | FK 는 좌석이 있을 때만 막는다. 좌석이 없는 운행편은 여전히 열려 있다 |
| **정규화의 조인 비용은 0 이 아니다** | 요청당 최대 6행을 더 읽는다. 실측으로 문제가 되면 재검토한다 |
| ~~기존 테스트 fixture 가 FK 적용 시 깨진다~~ | *(→ [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §9 에서 `MySqlTestSupport` 헬퍼 한 곳만 고쳐 해소했다. 기존 321개 테스트는 수정 없이 통과한다)* |
| **실험 사본과 운영 테이블의 차이** | V1 은 컬럼·인덱스가 더 많다. *(→ [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) §5 에서 확인했다. **계획은 실제로 달라졌고**, 통계가 최신이 아니면 조인 순서가 뒤집혀 `STRAIGHT_JOIN` 으로 고정해야 했다)* |
| **비정규화 재검토 기준이 없다** | "실측으로 문제가 되면" 의 임계값을 정하지 않았다 |

---

## 15. Task 2G-E-B2 적용 범위

> **완료됨 → [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md).** 아래 목록 중 트리거를 제외한 전부가 적용됐다.
> 적용 과정에서 §12 의 마이그레이션 구조(V5 backfill)와 §7 의 측정 조건(통계 최신)이
> 정정됐다. 각각 위 §12 와 §7 의 정정 문구를 보라.

| # | 계획한 것 | 실제 |
|---|---|---|
| 1 | **V4** — `sale_event` · `train_schedule` 생성 (`sale_event_id` FK 포함) | ✅ 그대로 |
| 2 | **V5** — 명시적 매핑 backfill | ❌ **마이그레이션으로 만들 수 없었다.** 매핑 원천이 없어 지어내야 하기 때문이다. **V4 와 FK 사이의 수동 운영 단계**가 됐다 |
| 3 | **V6** — orphan 0 확인 후 `seat_inventory.schedule_id` FK 추가 | ✅ 내용은 그대로, **번호는 V5** (2번이 파일이 아니게 됐으므로). **V6 은 존재하지 않는다** |
| 4 | `MySqlTestSupport` 헬퍼가 운행편을 먼저 보장 | ✅ 그대로. 기존 321개 테스트 무수정 통과 |
| 5 | 운영 저장소 — 조회, **재배정 UPDATE 미제공** | ✅ 그대로. 범위 해석 저장소가 추가됐다 |
| 6 | 회차 전이의 조건부 UPDATE | ✅ 그대로 |
| 7 | 트리거 적용 여부 판단 | ✅ 판단함 — **적용하지 않는다.** 마이그레이션 계정 분리가 전제다 |

계획에 없었으나 구현 중 드러나 추가된 것: **`STRAIGHT_JOIN` 으로 조인 순서 고정**
(§7 의 측정 조건 정정), 조회에서 **`DISTINCT` 제거**(누락 좌석 탐지).

---

## 16. 재현 명령

```bash
./gradlew :modules:reservation-infra:test \
  --tests "*SaleEventScopeLookupPlanTest" \
  --tests "*TrainScheduleMembershipImmutabilityExperimentTest" \
  --tests "*SaleEventMigrationCompatibilityExperimentTest"
```

Docker 가 실행 중이어야 한다. 실험 테이블(`task2geb1_*`)은 테스트가 직접 만들고 지운다
(운영 마이그레이션 없음).
