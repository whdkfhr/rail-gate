# TASK-002H-D — 만료 좌석 회수와 quota 감소의 그룹별 트랜잭션 연결

> 관련: [INVARIANTS.md](../INVARIANTS.md) I-10·I-11·I-12,
> [TASK-002D](TASK-002D-seat-expiry-sweeper.md) — 만료 저장소,
> [TASK-002G-C](TASK-002G-C-expiry-quota-attribution.md) — 감소량은 실제 변경 수,
> [TASK-002G-F](TASK-002G-F-expiry-sweeper-operating-contract.md) — 운영 계약 8가지 (이 Task 가 구현한 것),
> [TASK-002H-A](TASK-002H-A-hold-application-service.md) · [B](TASK-002H-B-release-application-service.md) · [C](TASK-002H-C-confirmation-application-service.md)

## 1. 목적과 범위

I-12 의 마지막 이탈 경로인 **만료 회수**를 quota 감소와 연결한다. 2G-F 가 실험으로 결정한
8가지 운영 계약을 **운영 코드로 옮긴 것**이며, 계약 자체를 바꾸지 않았다.

> ### ★ 네 경로가 서비스 수준에서 연결됐다 — 그러나 운영 활성화 완료가 아니다
>
> 선점(2H-A)·자발적 해제(2H-B)·확정(2H-C)·만료(이 Task) 네 경로 모두 quota 와 같은 트랜잭션으로
> 묶였다. 그래도 아직 아니다.
>
> | 잔여 조건 | 상태 |
> |---|---|
> | **주기 실행(`@Scheduled`)** | 없다. 이 서비스는 명시적으로 부르는 **한 번의 배치**다. 부르는 주체가 없다 |
> | **기존 데이터 backfill** | 절차만 있다(2G-G §4). 카운터 없는 홀드는 배치에서 `QUOTA_ROW_MISSING` 으로 남는다 |
> | **REST API·멱등키** | 없다. 실제 HTTP 요청은 여전히 어느 서비스도 통과하지 않는다 |
> | **결제 승인 검증(I-15)** | 없다 |
> | **감사 로그·메트릭** | 없다. 손상 후보·실패 그룹은 결과 객체로 돌려줄 뿐 **아무 데도 기록되지 않는다** |

**하지 않은 것** — `@Scheduled`, 잡 락(ShedLock), REST API, 결제 승인, 멱등키, migration·backfill,
감사 로그·메트릭. `Task2gfExpirySweeper`(테스트 전용 대안 비교 코드)를 운영에 복사하지 않았다.

---

## 2. 후보 조회와 회수 계약

| 항목 | 계약 | 근거 |
|---|---|---|
| 후보 | DB `NOW(3)` 기준 HELD/PAYING 만료. `ORDER BY expires_at, id`, `LIMIT` | 규칙 7, 2D 공정성 |
| 귀속 | `train_schedule` 실제 조인으로 `sale_event_id`, 좌석 행의 `hold_id`·`held_by` 함께 | 2G-F 실험 A |
| 손상 후보 | `hold_id` 또는 `held_by` NULL — **후보로 읽어 분리 보고, 회수 안 함**. 숨기거나 사용자 0 으로 바꾸지 않는다 | 2G-F 실험 F |
| 스냅숏 | 후보는 조회 시점의 사실. 실제 회수는 기존 조건부 UPDATE 가 `(id, hold_id)`·활성 상태·`expires_at <= NOW(3)` 을 다시 검사 | I-11 |
| 회수 | 그룹 안 좌석 PK 오름차순, 단일 벌크 UPDATE, `FORCE INDEX (PRIMARY)` — 기존 `expire` 그대로 | 규칙 2·3 |
| 감소량 | 요청·후보 수가 아니라 **`expire` 의 실제 `affected_rows`**. 0 이면 호출 안 함 | 2G-C |

### ★ 실패 후보가 앞을 막는 문제와 탐색 정책

**발견한 구멍.** 후보 조회는 `(expires_at, id)` 오름차순의 **첫 페이지만** 읽었다.
회수되지 않는 후보(손상·quota 부재)는 후보 집합에서 사라지지 않으므로 앞자리를 **영구히
차지한다.** 그것이 조회 크기를 채우면 다음 실행도 같은 후보만 보고 뒤의 정상 좌석에 끝내
도달하지 못한다. **그룹별 트랜잭션 격리는 후보 목록 *안*의 그룹끼리만 보호하고, 목록에
들어오지도 못한 좌석은 보호하지 못한다.**

**재현**(`ExpirySweepProgressTest`, 실제 MySQL·운영 서비스): 오래된 실패 후보 1석 + 그보다
나중에 만료된 정상 좌석 1석, 조회 크기 1로 3회 실행 → 손상·quota 부재 두 경우 모두
정상 좌석이 `HELD` 로 남고 quota 도 그대로였다 (§7 RED E).

**채택한 정책 — `(expires_at, id)` 키셋 커서 페이징.**

| 항목 | 값 |
|---|---|
| 전진 방식 | `AND (s.expires_at > ? OR (s.expires_at = ? AND s.id > ?))` — **하한을 풀어 쓴다.** 두 시각 파라미터에는 DB 에서 읽은 같은 커서 값을 넣는다 |
| **OFFSET 을 쓰지 않는 이유** | 앞 페이지의 좌석이 회수되어 후보 집합에서 **사라지므로**, 건너뛸 행 수로 위치를 잡으면 그만큼 **정상 후보를 건너뛴다** |
| 커서 전진 지점 | 페이지의 **마지막** 행. 성공·실패·손상을 가리지 않고 본 것 전부를 지난다 |
| tie-breaker | `id`. 없으면 만료 시각이 같은 좌석이 페이지 경계에서 누락된다 |
| 규칙 7 | 만료 **판정**은 여전히 `expires_at <= NOW(3)`. 커서는 DB 에서 읽은 정렬 키를 되돌려 주는 **위치 표식**이라 클럭 드리프트가 개입할 자리가 없다 |

**예산 — 페이지 크기와 실행 예산은 다른 것이다.**

- `pageSize` — 후보 SELECT **한 번이 돌려주는 행 수의 상한**(`LIMIT`). 한 쿼리가 반환하는 양을
  정할 뿐이며, **실제로 스캔하는 행 수의 상한도 벌크 UPDATE 크기의 상한도 아니다.**
- `maxPages` — 한 실행이 issue 할 수 있는 후보 SQL 수. **실패 후보를 몇 페이지까지 넘어가 볼 것인가.**
- 한 실행이 검사하는 후보 수의 상한 = `pageSize × maxPages`. **무한 루프도 전체 스캔도 아니다.**

실패 후보를 지나가기 위해 필요한 것은 **페이지 수**이지 페이지 크기가 아니다.

**`pageSize` 는 벌크 UPDATE 크기를 정하지 않는다.** 구현은 **여러 페이지를 먼저 모은 뒤**
`(saleEventId, userId)` 로 그룹화하고, 한 번의 벌크 UPDATE 는 **수집된 그 그룹의 후보 전부**를
대상으로 한다. 한 그룹의 좌석이 여러 페이지에 흩어져 있으면 UPDATE 가 `pageSize` 보다 커지고,
`maxPages` 를 늘리면 수집량이 늘어 **각 UPDATE 도 커질 수 있다.**

배치가 거는 유일한 크기 상한은 **한 실행의 수집 후보 수 `pageSize × maxPages`** 다.
정상 데이터라면 한 그룹의 활성 좌석이 P-2(1인당 4석)를 넘지 않지만, 그것은 **데이터가 정상일 때
성립하는 성질**이지 배치가 강제하는 제한이 아니다 — drift 가 있으면 한 그룹이 4석을 넘을 수 있다.

기본값 `DEFAULT_MAX_PAGES = 4` 는 **측정으로 정한 값이 아니다.** 실패 블록이 이보다 깊으면
한 번의 실행으로는 통과하지 못하고 호출자가 이어가야 한다 — 그 계약이 진행을 보장한다.

### ★ 공개 호출 계약 — 진행 보장은 어떤 호출 아래 성립하는가

```java
ExpirySweepResult sweep(int pageSize)                 // 처음부터, 기본 페이지 예산
ExpirySweepResult sweep(ExpirySweepRequest request)   // 명시적 범위·예산·continuation
```

`sweep(int)` 의 **파라미터 의미가 바뀌었다.** 전에는 "이 실행이 읽는 후보 수" 였고 지금은
"페이지 크기" 다 — 한 실행은 그 크기의 페이지를 최대 `DEFAULT_MAX_PAGES` 개까지 읽는다.
호출부·테스트·javadoc 을 함께 맞췄다.

**한 번의 호출은 한 순회의 한 구간이다.** 순회는 여러 호출에 걸쳐 이어지고, 끝나면 다음 순회가
처음부터 시작한다.

| 상태 | 신호 | 호출자가 할 일 |
|---|---|---|
| **예산 소진** | `maxPages` 를 다 썼고 마지막 페이지가 꽉 참 → `nextCursor` 에 위치 | **`request.continueAfter(cursor)` 로 같은 순회를 이어간다.** 진행은 이때만 보장된다 |
| **순회 완료** | 어떤 페이지가 `pageSize` 보다 적게 옴 → `nextCursor` **비어서** 반환 | 다음 순회를 `fromStart` 로 **처음부터** — 실패 후보 재확인이 목적이다 |

### ★ `sweep(int)` 반복만으로는 진행이 보장되지 않는다

`sweep(int)` 와 `fromStart` 는 **매번 처음부터** 조회한다. 따라서 **실패 후보가 실행 예산
(`pageSize × maxPages`)을 채우면 반복 호출만으로는 그 뒤의 정상 좌석에 끝내 도달하지 못한다** —
매 호출이 같은 실패 후보만 다시 읽는다. `ExpirySweepProgressTest` 가 예산을 정확히 채우는 실패
후보로 이 차이를 고정한다: `sweep(int)` 3회 → 정상 좌석 `HELD`, continuation 소비 → `AVAILABLE`.

순회 완료 후 처음부터 다시 시작하는 것은 **실패 후보 재확인**을 위한 것이지 진행을 위한 것이 아니다.

**보관 주체는 호출자다. 서비스는 어떤 진행 상태도 갖지 않는다.**

| | 서비스 내부 상태(필드) | **명시적 continuation (채택)** |
|---|---|---|
| 다중 스위퍼 | 인스턴스마다 위치가 달라 동작이 인스턴스 수에 좌우된다 | 위치가 인자다. 무상태라 어느 쪽이 불려도 같다 |
| 재시작 | 죽으면 위치가 사라지고, 살아 있으면 **영원히 앞을 다시 안 본다** — 손상이 고쳐져도 모른다 | 처음부터 볼지 이어갈지 매번 정한다 |
| 후보 시각 변경 | 연장·재선점으로 순서가 바뀌면 내부 위치가 의미를 잃는데 알 방법이 없다 | 커서는 **한 순회 안에서만** 유효하고, 새 순회는 커서를 버린다 |

**실패 후보 재확인.** 새 순회는 커서를 버리고 처음부터 본다. 그래서 지나쳤던 실패 후보는
다음 순회에서 반드시 다시 검사된다 — 고쳐졌으면 회수되고, 그대로면 다시 실패로 보고된다.
**숨겨지지 않는다.** 손상 후보를 후보에서 빼거나, 실패 좌석을 임의 해제하거나,
quota 를 자동 보정하지 않는다.

**자동 스케줄 실행은 추가하지 않았다.** 진행 보장은 공개 호출 흐름 위에서만 성립하며,
`ExpirySweepProgressTest` 가 그 흐름(반복 호출 / 커서 이어가기)으로 검증한다.

### 새 조회 SQL 과 실행 계획

`JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_SQL` — 기존 `FIND_CANDIDATES_SQL` 과 같은 인덱스·정렬·LIMIT 에
`train_schedule` STRAIGHT_JOIN 이 붙고, **`hold_id IS NOT NULL` 이 빠졌다** (손상 행을 읽기 위해).
기존 `findExpiredCandidates` 계약은 그대로 두고 `findExpiredSeats` 로 이름을 구분했다.

### ★ 행 생성자 비교를 버린 이유 — 실측으로 바로잡았다

처음에는 `AND (s.expires_at, s.id) > (?, ?)` 행 생성자 비교를 쓰고 "복합 인덱스의 범위 시작점으로
그대로 쓰인다" 고 적었다. **측정해 보니 틀렸다.** 이 SQL 의 다른 조건(`expires_at <= NOW(3)`,
`status IN (...)`)과 함께 놓이면 MySQL 8.4 는 **`expires_at <= NOW(3)` 만으로 범위를 잡고 그 안을
전부 훑으면서** 행 생성자 조건을 필터로 적용했다.

`SeatExpiryCursorScanTest` 의 `Handler_read%` 세션 카운터 실측 — 만료 시각이 **완전히 같은 좌석 600행**,
`LIMIT 1` 조회:

| 커서 SQL | 커서가 앞을 가리킬 때 | 커서가 마지막 직전 |
|---|---|---|
| `(expires_at, id) > (?, ?)` | 3행 | **601행** ← 선형 증가 |
| `expires_at > ? OR (expires_at = ? AND id > ?)` | 3행 | **3행** |

커서가 뒤로 갈수록 읽는 행이 선형으로 늘었다 — **키셋 페이징의 목적 자체가 무너진다.**
그래서 하한을 명시적으로 풀어 썼다. `OR` 이 들어가지만 두 갈래 모두 같은 인덱스의 범위라
옵티마이저가 하한을 인식한다.

**EXPLAIN 으로는 증명되지 않는다.** `type = range` 나 인덱스 이름은 커서 하한의 증거가 아니다 —
`expires_at <= NOW(3)` 하나만으로도 같은 인덱스의 range 가 나온다. 그래서 이 성질은
**읽은 행 수**로만 검증한다. 절대 실행 시간은 쓰지 않는다.

EXPLAIN 어서션(`JdbcSeatExpiryRepositoryTest`, 만료 30석)이 고정하는 것은 그보다 약하다 —
두 SQL 모두 `s` 가 `idx_seat_inventory_expiry_candidate`(V2)를 쓰고 filesort 가 없으며
`ts` 가 PRIMARY/`eq_ref` 라는 것까지다. 커서 값은 **DB 에서 읽어** 넣는다 (JVM 시계로 만들면
세션 시간대 차이로 조건이 항상 거짓이 되어 계획이 접힌다 — 실제로 관측했다).

**새 인덱스도 migration 도 추가하지 않았다** — 키셋이 기존 `(expires_at, id)` 인덱스를 그대로 쓴다.
힌트를 뺐을 때의 계획과 대규모 backlog 에서의 비용은 측정하지 않았다.

---

## 3. 트랜잭션 구조와 잠금 순서

```
ExpireHoldsService.sweep(batchSize)          ← 트랜잭션 밖. 활성 TX 가 있으면 읽기 전에 거절
  1. findExpiredSeats(pageSize, cursor)      잠금 없는 스냅숏. 예산만큼 페이지를 넘기며 수집
  2. (saleEventId, userId) 그룹화, 손상 분리   키 오름차순, 그룹 안 PK 오름차순
  3. 그룹마다:
     ┌ TransactionTemplate(REQUIRED) ─────────────────────────────┐
     │ quota.lockRow(회차, 사용자)   행 없음 → QUOTA_ROW_MISSING (좌석 UPDATE 전 실패) │
     │ expiry.expire(그룹)           PK 순 벌크 UPDATE → affected                 │
     │ affected == 0 → 커밋 (감소 없음)                                          │
     │ quota.release(…, affected)    REJECTED → QUOTA_DECREASE_REJECTED (회수까지 롤백) │
     └────────────────────────────────────────────────────────────┘
     일시적 실패 → 백오프(TX 밖) → 새 TX 로 재시도, 최대 3회
  4. 커밋된 그룹만 succeeded 에 집계
```

### 왜 배치 전체가 아니라 그룹마다인가 (2G-F 실험 B)

한 사용자의 카운터 부재가 **모든 사용자의 회수를 막는다.** RED B 가 그것을 재현했다 — 배치 전체를
한 트랜잭션으로 묶자 B 의 부재 때문에 A 의 회수까지 롤백됐다.

### 왜 카운터 부재를 좌석 UPDATE 전에 실패시키는가 (2G-F 계약 3)

해제(2H-B)·확정(2H-C)은 실제로 바뀐 뒤 판단한다 — 사용자가 기다리는 요청이라 0 건이면 행 없어도
no-op 이어야 하기 때문이다. 배치는 반복 실행이라 카운터 없는 그룹의 좌석을 UPDATE 했다 되돌리는
비용을 매번 치를 이유가 없다. 잠금 단계에서 실패시키고 좌석은 건드리지 않는다.

### 외부 트랜잭션 거절

그룹 TX 는 `REQUIRED` 다. 바깥에 TX 가 있으면 그룹들이 그 하나에 참여해 독립 커밋이 사라지고,
호출자의 롤백이 성공으로 집계한 그룹까지 되돌린다. `REQUIRES_NEW` 로 두면 호출자의 롤백 기대와
무관하게 커밋되어 더 위험하다. 그래서 **활성 TX 가 있으면 아무것도 읽기 전에 거절**한다 (RED D).

### self-invocation 문제가 없는 이유

`@Transactional` 프록시가 아니라 `TransactionTemplate` 으로 경계를 연다. 같은 클래스 안에서 부르든
손으로 조립하든 경계는 같다. 그래서 통합 테스트가 결정적 시나리오에 손으로 조립한 인스턴스를 써도
검증 강도가 떨어지지 않으며, 경계의 실재는 §3·§5 의 **롤백 관측**이 증명한다.

---

## 4. 재시도와 백오프

| 항목 | 계약 (2G-F 계약 5) |
|---|---|
| 단위 | 실패한 그룹만. 성공 그룹은 다시 실행하지 않는다 |
| 대상 | `DEADLOCK`(1213) · `LOCK_WAIT_TIMEOUT`(1205) · `TRANSIENT_TRANSACTION_ROLLBACK`(vendor code 없는 `SQLTransactionRollbackException`) · `TRANSIENT_LOCK_FAILURE`(vendor code 없는 Spring `PessimisticLockingFailureException`) |
| 분류 | cause 사슬 전체에서 **vendor code 우선** — NESTED savepoint 가 1213 을 1305 로 덮어도 사슬에 남는다(2G-B). 재시도 여부와 원인 분류는 별개 |
| 비대상 | `QUOTA_ROW_MISSING` · `QUOTA_DECREASE_REJECTED` · `UNKNOWN` — 1회로 끝, 백오프 없음 |
| 횟수 | 최초 포함 최대 3회 → 백오프 최대 2회 |
| 트랜잭션 | 재시도마다 새 TX. **백오프는 TX 종료 후** (테스트가 백오프 시점의 TX 부재를 확인) |
| 인터럽트 | 삼키지 않는다. 플래그를 복원하고 `IllegalStateException(cause=InterruptedException)`. 이미 커밋된 그룹은 그대로 |

### 백오프 정책 — `50ms → 100ms`, 상한 `200ms`

데드락(1213)은 피해자가 롤백된 순간 상대가 이미 진행 중이라 즉시 재시도해도 대부분 성공한다.
잠금 대기 초과(1205)는 이미 3초를 기다린 뒤라 긴 백오프를 더할 이유가 없다. 짧게 시작해 두 배로
늘리되 상한을 둔다. 최대 누적 150ms. **측정으로 정한 값이 아니다** — 경합 프로파일을 측정하면
조정한다. 이 값이 보장하는 것은 배치 한 번의 지연 상한이 예측 가능하다는 것뿐이다.
테스트는 대기하지 않는 구현을 주입해 호출 횟수·시점만 검증한다.

---

## 5. 포트와 배선

| 포트 | 연산 | 비고 |
|---|---|---|
| `SeatExpiryPort` (신규, domain) | `findExpiredSeats(int pageSize, Optional<ExpiryCursor> after)`, `expire(List<ExpiryCandidate>)` | `JdbcSeatExpiryRepository` 구현 |
| `UserHoldQuotaPort` | `lockRow`·`release` 재사용 | 변경 없음 |

- `ExpiryCandidate` infra → domain **이동** (복제 없음). 신규 순수 Java `ExpiredSeat`(귀속·손상 포함)
- 결과·분류·재시도 타입은 application 에: `ExpirySweepResult`, `QuotaGroupKey`, `GroupFailureKind`,
  `GroupSettlementException`(`QuotaInconsistencyException` 의 하위), `GroupFailureClassifier`, `RetryBackoff`
- 배선: `SeatExpiryPort`·`RetryBackoff` 빈 추가. 선점·해제·확정과 같은 DataSource·`DataSourceTransactionManager`

---

## 6. 검증 결과

### 단위 — `ExpireHoldsServiceTest` (15개)

실제 `AbstractPlatformTransactionManager` 를 상속한 기록 관리자로 begin/commit/rollback 순서를 본다.
순서(`find → begin → lockRow → expire → release(n) → commit`), 부분 stale 감소량, 0건 감소 미호출,
키 오름차순·PK 오름차순, 후보 없음 → TX 없음, 행 부재 → UPDATE 전 실패·1회·백오프 없음, 감소 거절 →
롤백·미재시도, 손상 격리·사용자 0 없음, UNKNOWN 미재시도, 데드락 → 새 TX 재시도·백오프 TX 밖,
3회 실패 → 시도 3·백오프 2·4번째 없음, 성공 그룹 미재실행, 두 `TRANSIENT_*` 별도 분류, 인터럽트 복원.

### 통합 — `ExpireHoldsServiceIntegrationTest` (17개, MySQL 8.4)

| 절 | 검증 | 방식 |
|---|---|---|
| §1 정상 (4) | 회수·감소, 다중 사용자·회차 각자 정산, 후보 없음, **외부 TX 거절(읽기 전)** | Spring 빈 |
| §2 stale (2) | 후보 조회 뒤 `ConfirmSeatService` 확정 → 후보 3·실제 2·quota 0(확정 1+회수 2), 후보 조회 뒤 `ReleaseHoldService` 해제 → 0건·감소 없음 | 손으로 조립 + 후보 조회 직후 훅 — **결정적** |
| §3 격리 (3) | 행 없는 그룹만 실패·정상 그룹 커밋·실패 그룹 좌석 불변(version), 부족 → 회수까지 롤백(4필드 동등), 손상 격리 | Spring 빈 |
| §4 두 스위퍼 (1) | `CyclicBarrier` 로 **둘 다 후보를 읽은 뒤** 정산 → 회수 2+0, 감소 한 번, REJECTED 없음 | 손으로 조립 — **결정적** ("같은 스냅숏" 을 강제) |
| §5 재시도 (2) | 첫 시도가 실제 UPDATE 후 1213 → 롤백(version 불변 확인)·백오프 TX 밖·새 TX 성공, 1205 ×3 → 실패·좌석·quota 불변 | 손으로 조립 + 실패 주입 데코레이터 |
| §6 RED C 대조군 (2) | 감소 거절을 삼키면 **커밋 후** 좌석 2석 `AVAILABLE`·카운터 1 잔존; 올바른 구현은 `HELD` 로 복구 | 손으로 조립 — 결정적 |

§3 `quota_부족_그룹은_회수까지_롤백된다` 는 **대상 두 좌석 모두**에 대해
`status`·`reservation_id`·`hold_id`·`held_by`·`held_at`·`expires_at`·`version` **7개 필드**를
호출 전후로 비교한다.

### 진행 보장 — `ExpirySweepProgressTest` (8개, MySQL 8.4, 운영 서비스 빈)

| 절 | 검증 |
|---|---|
| §1·§2 (2) | **손상 후보·quota 부재 후보가 앞을 막아도** 조회 크기 1로 반복 호출하면 뒤의 정상 좌석이 회수된다. 실패 후보 데이터는 보존되고 quota 행도 만들지 않는다 |
| §3 (4) | 실패 후보 3석 > 조회 크기 1 — 예산이 충분하면 한 번에 도달 / 예산이 부족하면 **`nextCursor` 를 실제로 소비해** 유한 횟수에 도달 / 순회 종료 후 처음부터 부르면 실패 후보를 **다시 확인**하고 고쳐지면 회수 / 같은 `expires_at` 4석이 페이지 경계에 걸려도 누락 없음 / 커서가 페이지 마지막까지 전진해 후보 중복 없음 |
| §4 (1) | **실패 그룹이 먼저**(그룹 키 오름차순)여도 뒤의 정상 그룹이 커밋된다 |
| §5 (1) | 탐색 중 확정·해제·기한 연장된 좌석은 회수되지 않고 감소도 실제 변경분만 |

### infra — 만료 저장소 (`JdbcSeatExpiryRepositoryTest` §귀속 포함 후보 조회 8개 + `SeatExpiryCursorScanTest` 4개)

귀속 반환 / 미만료·AVAILABLE·SOLD 제외 / 손상 후보 포함·사유 구분·기존 조회는 계속 제외 /
정렬·LIMIT / `pageSize` 검증 / 커서 뒤부터 읽고 같은 만료 시각은 id 로 갈림 / 앞 후보가 사라져도
건너뛰지 않음 / 두 SQL 의 EXPLAIN.

`SeatExpiryCursorScanTest` 는 **읽은 행 수**로 커서 하한을 검증한다 — 커서가 뒤쪽을 가리켜도
남은 행만 읽는가, 커서 앞의 후보를 늘려도 스캔이 늘지 않는가, 하한을 적용해도 결과 집합이 같은가,
첫 페이지는 LIMIT 만큼만 읽는가. 절대 실행 시간을 쓰지 않는다.

### 주장의 층

| 층 | 검증 |
|---|---|
| **결정적** | §2 stale(훅이 순서를 고정), §4 두 스위퍼(배리어가 "둘 다 읽은 뒤" 를 강제), §3·§5 롤백 관측 |
| **동시 실행의 관측** | §4 에서 **누가 먼저 quota 를 잠그는지**. 결과의 합만 확인한다 |
| **구조적 논증** | quota → seat 순서로 같은 그룹의 TX 가 직렬화되고, 감소가 항상 `affected_rows` 를 따르므로 이중 감소가 없다 |

---

## 7. ★ 먼저 실패시킨 기록 (규칙 27)

> **실행 시점과 관측 범위를 구분한다.** 각 RED 의 **최초 실패 지점**과 **그 때문에 도달하지
> 못한 검증**을 나눠 적는다. 실행 시점이 서로 다르므로 실패 수를 한 표로 합치지 않는다.
>
> - **RED G** — 이번 라운드에서 실행했다 (커서 SQL 수정 전 상태를 그대로 측정).
> - **RED A·C·D·E·F** — 키셋 페이징을 넣던 이전 라운드에서, 그때의 스위트
>   당시 스위트(단위 15 + 통합 14 + 진행 보장 8 = 37)에 대해 운영 코드를 임시로 바꿔 실행했다.
>   **이번 라운드에서 재실행하지 않았다** — 그 뒤 테스트가 늘었으므로 현재 스위트 기준
>   실패 수는 다를 수 있다.
> - **RED B** — 그보다 앞선 라운드(통합 12개 시절)의 기록이다. 아래에 따로 적는다.
>
> 모든 경우 원복 후 파일이 바이트 동일함을 확인했다.

### RED E — 페이지를 넘기지 않는다 (이번 회귀의 핵심)

| 항목 | 값 |
|---|---|
| 순진한 구현 | `while (pagesRead < request.maxPages())` → `while (pagesRead < 1)` — 첫 페이지만 |
| 실패 | **5개** — 진행 보장 §1 손상, §2 quota 부재, §3 예산 충분·경계 tie, §3 순회 종료 후 재확인 |
| 최초 실패 지점 | 좌석 상태 어서션(`statusOf(normal)` = `HELD`). **DB 상태를 직접 관측했다** — 후보 목록에 들어오지 못한 좌석이 회수되지 않은 채 남는다 |
| 도달하지 못한 검증 | quota 값 어서션. 좌석이 이미 어긋났으므로 그 뒤는 보지 못했다 |

**이것이 수정 전 구현의 상태다.** 정책을 넣기 전에 먼저 이 두 테스트(§1·§2)로 재현했고,
그때도 같은 지점에서 실패했다.

### RED G — 행 생성자 비교로 커서 하한을 걸려는 시도 (이번 라운드에서 실행)

| 항목 | 값 |
|---|---|
| 순진한 구현 | `AND (s.expires_at, s.id) > (?, ?)` — "복합 인덱스의 시작점으로 쓰인다" 는 가정 |
| 실패 | **2개** — `SeatExpiryCursorScanTest` 의 `커서가_뒤쪽을…`, `커서_앞의_후보를…` |
| 최초 실패 지점 | **읽은 행 수 어서션.** 같은 만료 시각 600행 중 마지막 1행을 읽는 데 **601행**을 훑었다 |
| 증가 양상 | 커서가 앞이면 3행, 마지막 직전이면 601행 — **선형 증가** |
| 고친 뒤 | 두 지점 모두 3행 |

**EXPLAIN 은 이 실패를 잡지 못했다.** 두 SQL 모두 `type = range` 에 같은 인덱스였다 —
`expires_at <= NOW(3)` 만으로도 그 계획이 나오기 때문이다. 그래서 검증을 스캔 행 수로 옮겼다.

### RED F — 커서를 페이지 첫 행으로만 전진

| 항목 | 값 |
|---|---|
| 순진한 구현 | `page.get(page.size() - 1).cursor()` → `page.get(0).cursor()` |
| 실패 | **1개** — 진행 보장 §3 `커서는_페이지_마지막까지_전진해…` |
| 최초 실패 지점 | `candidatesFound` 가 4 가 아니다 — 같은 좌석이 후보에 중복됐다 |
| 관측 범위 | **페이지 크기 1 에서는 드러나지 않는다.** 첫 행과 마지막 행이 같기 때문이다. 크기 2 로 보는 테스트를 그래서 따로 두었다 |

### RED A — `affected` 대신 후보 수만큼 감소

| 항목 | 값 |
|---|---|
| 실패 | 2 — 단위 부분 stale, 통합 §2 확정 stale |
| 최초 실패 지점 | 단위는 호출 기록(`quota.release(9001,1,2)` 가 아니라 `(…,3)`). 통합은 `failed()` 가 비지 않음 — 실제보다 큰 감소가 `REJECTED` 됐다 |
| 도달하지 못한 검증 | 통합의 quota 최종값 어서션 |

### RED C — 감소 거절을 삼키고 성공 반환

| 항목 | 값 |
|---|---|
| 실패 | 2 — 통합 §3 부족, 단위 감소 거절 |
| 최초 실패 지점 | **`failed()` 목록이 비었다는 것까지다.** 이전 판의 "예외 미발생·좌석이 AVAILABLE 로 커밋됨을 관측" 은 **틀렸다** — 그 어서션이 먼저 실패해 뒤의 좌석 스냅숏 비교에 도달하지 못한다 |
| 커밋 후 상태 | **§6 대조군이 따로 실측한다.** 감소 결과를 보지 않는 대조군을 트랜잭션 안에서 돌리면, 커밋 후 좌석 2석이 `AVAILABLE` 이고 카운터는 `1` 그대로다 — **좌석 0석인데 상한 1 을 소진한 채** 남는다 |

### RED D — 외부 TX 거절 제거

| 항목 | 값 |
|---|---|
| 실패 | 1 — 통합 §1 외부 TX |
| 최초 실패 지점 | 예외가 나오지 않음(`assertThatThrownBy`). 거절 없이 바깥 TX 에 참여해 정상 실행됐다 |
| 도달하지 못한 검증 | "아무것도 바뀌지 않았다" 좌석·quota 어서션 |

### RED B — 배치 전체를 한 TX 로 (이전 판에서 실행, **이번에 재실행하지 않았다**)

이전 판(단위 15 + 통합 12)에서 11개 실패를 관측했고 핵심은 통합 §3 `quota_행_없는_그룹만…`
과 단위 `quota_행이_없으면…` 이었다 (2G-F 실험 B 재현 — B 의 카운터 부재가 A 의 회수를
롤백시킨다). **이번 변경 뒤 재실행하지 않았으므로 현재 스위트 기준 실패 수는 모른다.**

---

## 8. 남은 위험

- **주기 실행 주체가 없다.** 이 배치는 아무도 부르지 않는다. `@Scheduled` 배선은 2G-F 의 다중 실행 계약(잡 락 없이 정합성 유지, ShedLock 은 측정 후)을 따를 예정이다
- **손상 후보와 실패 그룹이 기록되지 않는다.** 결과 객체로만 돌아오므로 호출자가 버리면 사라진다. 감사 로그·메트릭이 후속이다
- **backfill 안 된 홀드는 매 배치 실패한다.** 의도된 동작이지만 활성화 전 backfill 이 필수다
- **백오프 값과 `DEFAULT_MAX_PAGES` 는 측정 근거가 없다**
- **실패 블록이 예산보다 깊으면 한 번의 실행으로는 통과하지 못한다.**
  `sweep(int)` 는 매번 처음부터 시작하므로 **반복 호출만으로는 뒤의 정상 후보에 도달하지
  못한다** — 매 호출이 같은 실패 후보만 다시 읽는다. 진행은 **예산이 소진됐을 때 반환된
  `nextCursor` 를 다음 요청에 전달할 때만** 보장된다. 순회를 마친 뒤 `fromStart` 로 다시
  시작하는 것은 **지나친 실패 후보를 재확인하기 위한 것**이지 진행을 위한 것이 아니다.
  **서비스는 스스로 다시 부르지 않으며**, 주기 실행 주체가 없으므로 이 계약은 아직 아무도 쓰지 않는다
- **실패 후보가 계속 쌓이면 매 순회의 앞부분을 점점 더 오래 지나가야 한다.** 손상·drift 를
  고치는 운영 절차가 없으면 예산이 잠식된다. 그 비율을 측정하지 않았다
- 미측정: 힌트 없는 계획, 대규모 backlog 비용, 그룹 TX 잠금 보유 시간, 데드락 실제 발생률, 배치 크기 튜닝
- §4 는 "둘 다 읽은 뒤" 만 고정한다. 정산 순서의 모든 교차를 시험한 것은 아니다

---

## 9. 전체 검증 결과

```
git diff HEAD --check                              → clean
./gradlew check --rerun-tasks --max-workers=1      → BUILD SUCCESSFUL (1m 26s)
```

이번 실행의 테스트 결과 XML 기준:

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `apps:reservation-service` | 131 | 0 | 0 | 0 |
| `modules:reservation-infra` | 560 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| **합계** | **894** | **0** | **0** | **0** |

테스트를 제외하거나 어서션을 완화하거나 `check` 의존성을 건드리지 않았다.

### 환경 — 이전 실패는 구현이 아니라 자원 문제였다

이전 두 라운드의 전체 `check` 는 `reservation-infra` 에서 MySQL Testcontainer 연결이 끊겨
실패했다. 당시 관측은 다음과 같다. **이 기록은 그때의 관측으로 보존한다.**

| 라운드 | 실패 | 원인 |
|---|---|---|
| 1차 | 505개 | `Communications link failure` 515건, 어서션 실패 0. 컨테이너 `Exited (137)` / `OOMKilled=true` |
| 2차 (`--max-workers=1`) | 359개 | `Communications link failure` 716건 + `Connection is closed` 2건, 어서션 실패 0 |

Docker VM 한도가 **1.93 GiB** 인데 다른 프로젝트 컨테이너 5개가 약 412 MiB 를 상시 사용하고 있어
데이터 볼륨 실험(좌석 60,000행 등)을 도는 MySQL 이 버티지 못했다. **`--max-workers=1` 만으로는
해결되지 않았다.**

이번 라운드는 **사용자 승인을 받아** 그 컨테이너들을 일시 중지하고 실행했으며, 실행 직후 모두
복구했다. 같은 코드가 자원이 확보되자 **1분 26초에 완주**했다 — 이전 실패가 환경 장애였음을
사후적으로 확인해 준다. 다만 **"환경 장애였다" 가 곧 "그 시점에 구현이 검증됐다" 는 뜻은 아니며**,
구현 검증의 근거는 이번 실행의 894개 통과다.

---

## 10. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*ExpireHoldsServiceTest"
./gradlew :apps:reservation-service:test --tests "*ExpireHoldsServiceIntegrationTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcSeatExpiryRepositoryTest"
./gradlew check --rerun-tasks
```
