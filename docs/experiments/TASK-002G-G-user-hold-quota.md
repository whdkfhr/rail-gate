# 적용 — `user_hold_quota` 운영 스키마와 저장소 (Task 2G-G)

> TASK-002G-G · 2026-09-08
> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.6·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [CLAUDE.md](../../CLAUDE.md) 규칙 8·9·10·25·26·27
> 선행: [2G-A](TASK-002G-user-hold-quota-race.md) · [2G-B](TASK-002G-B-quota-lock-order.md) ·
> [2G-C](TASK-002G-C-expiry-quota-attribution.md) · [2G-D](TASK-002G-D-quota-scope-contract.md) ·
> [2G-E-B2](TASK-002G-E-B2-sale-event-persistence.md) · [2G-F](TASK-002G-F-expiry-sweeper-operating-contract.md)

## 1. 목적과 범위

2G-A~F 가 실험으로 확정한 quota 계약을 **운영 코드로 옮긴다.** 세 가지다.

1. `user_hold_quota` 운영 마이그레이션 (V6)
2. 운영 quota 저장소 (`JdbcUserHoldQuotaRepository`)
3. 읽기 전용 drift 탐지 SQL (`load-test/verify/user_hold_quota_drift.sql`)

**만들지 않은 것** — 애플리케이션 서비스, REST API, `@Scheduled`, 운영 재시도 컴포넌트,
메트릭·감사 로그, 스위퍼 배선. 전부 Task 2H 다.

> ### ★ I-12 는 아직 요청 경로에 강제되지 않는다
>
> 이 Task 가 만든 것은 **불려졌을 때 원자적인 저장소**다. 선점 유스케이스가
> "quota 확보 → 좌석 선점" 을 한 트랜잭션에서 부르도록 배선하고, 확정·만료·해제 세 이탈
> 경로에서 감소를 연동해야 비로소 강제된다. **그 배선이 없으면 이 저장소는 아무도 부르지
> 않는 코드다.**

---

## 2. 옮겨 온 계약

| 계약 | 출처 | V6·저장소에서의 모습 |
|---|---|---|
| 집계가 아니라 **카운터 행의 조건부 UPDATE** | 2G-A | `TRY_ACQUIRE_SQL` 의 `WHERE held_seats + ? <= 4` |
| 범위는 `sale_event_id` | 2G-D | PK 선두 컬럼, `sale_event` FK |
| 잠금 순서 quota → seat, 키 오름차순 | 2G-B | PK 순서 = `QuotaKey.compareTo`, `lockAll` 이 정렬 |
| 감소량은 **실제 좌석 변경 행 수** | 2G-C | `release(…, affectedRows)` |
| 없는 행을 자동 생성하지 않는다 | 2G-F 실험 C | `release`·`tryAcquire` 가 `WHERE` 로 걸러 0행 |
| `INSERT IGNORE` 금지 | 2G-A | `ON DUPLICATE KEY UPDATE held_seats = held_seats` |

---

## 3. V6 마이그레이션

```sql
CREATE TABLE user_hold_quota (
    sale_event_id BIGINT   NOT NULL,
    user_id       BIGINT   NOT NULL,
    held_seats    SMALLINT NOT NULL DEFAULT 0,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sale_event_id, user_id),
    CONSTRAINT ck_user_hold_quota_range CHECK (held_seats >= 0 AND held_seats <= 4),
    CONSTRAINT fk_user_hold_quota_sale_event FOREIGN KEY (sale_event_id) REFERENCES sale_event (id)
);
```

- **PK 순서가 곧 잠금 순서다** (2G-B). `sale_event_id` 가 선두라 FK 가 요구하는 인덱스도
  이 PK 가 충족한다 — 보조 인덱스를 따로 만들지 않았다.
- **CHECK 는 최후의 방어선**이다. 조건부 UPDATE 가 실패해도 잘못된 값이 저장되지 않는다.
  음수 방어는 이중 감소와 과대 계산을 막는다.
- 시간 컬럼은 다른 테이블과 같이 `DATETIME(3)`.
- V1~V5 는 수정하지 않았다.

---

## 4. ★ 적용 절차 — 스키마와 강제 활성화는 별개다

### 테이블은 비어 있는 채로 만든다

**backfill 을 마이그레이션에 넣지 않았다.** 이유는 하나다.

> **I-12 는 한 번도 강제된 적이 없다.** 그래서 상한을 넘는 사용자가 이미 있을 수 있고,
> 그 값은 `ck_user_hold_quota_range` 를 통과하지 못한다.

테스트로 재현했다 — 한 사용자가 6석을 잡고 있는 스키마에서 좌석 집계를 그대로 넣으면
CHECK 위반으로 실패하고, **부분 반영도 남지 않는다.** 어느 좌석을 빼야 하는지는 데이터가
답하지 않는다. **정책 결정이 필요하고, 마이그레이션이 그것을 대신 정하면 안 된다.**

비어 있어도 지금은 안전하다 — **아직 아무도 이 표를 읽지 않기 때문**이다.

### 스키마 배포와 강제 활성화는 별개다

| | 무엇을 하나 | 중단 필요 | 지금 가능한가 |
|---|---|---|---|
| **스키마 배포** | 빈 표를 만든다 | **불필요** — 아무도 읽지 않는다 | ✅ |
| **강제 활성화** | 요청 경로가 quota 를 검사하게 한다 | **필요** (아래) | ❌ 배선이 없다 (Task 2H) |

### [A] fresh 설치

```bash
flyway migrate        # V1 → … → V6 까지 그대로 이어진다
```

좌석이 없으므로 카운터도 비어 있는 것이 맞다. **생략할 수 있는 것은 기존 데이터 backfill(c)과
그 검증(d)뿐이다** — 집계할 좌석이 애초에 없기 때문이다.

**e(모든 쓰기 경로가 quota 를 같은 트랜잭션으로 다루는 버전인지 확인)는 생략할 수 없다.**
구버전 인스턴스가 quota 없이 좌석을 잡으면 카운터는 첫 요청부터 어긋난다.
쓰기를 열기 전에 배선과 버전을 확인해야 하는 것은 fresh 설치도 같다.

### [B] 기존 활성 좌석이 있는 배포

**스키마 배포는 무중단으로 해도 된다.**

```bash
flyway migrate        # V6 까지 적용. 좌석을 건드리지 않는다
```

**강제 활성화는 다르다.** 아래 전제가 모두 충족돼야 한다.

> **★ 순서만으로는 부족하다.** "backfill → drift 0행 → 활성화" 를 순서대로 해도,
> **검증 직후에 기존 쓰기 경로가 좌석을 바꾸면 그 검증은 즉시 낡은 것이 된다.**
> 선점 하나가 끼어들면 카운터는 그 좌석을 모른 채 활성화된다.
> 그래서 (a)(b) 로 **쓰기를 멈춘 뒤에** (c)(d) 를 한다.

| 단계 | 할 일 | 중단 기준 |
|---|---|---|
| **a** | 관련 쓰기 경로 중단 — 선점, 결제 상태 전이, 확정, 해제, 만료 | — |
| **b** | 모든 인스턴스의 배치·스위퍼·비동기 콜백까지 통제하고, **진행 중이던 관련 트랜잭션이 끝났는지 확인** | 종료를 확인하지 못하면 진행하지 않는다 |
| **c** | 이상 데이터 점검 후 **운영자가 명시적으로** backfill | 상한 초과 또는 소유자 누락이 **한 행이라도** 나오면 중단 |
| **d** | **쓰기가 멈춘 상태에서** drift SQL 의 세 결과(V-7a·V-7b·V-7c)가 모두 0행 | 한 행이라도 나오면 중단 |
| **e** | 모든 쓰기 경로가 **quota 증감을 좌석 변경과 같은 트랜잭션으로** 수행하는 버전인지 확인. 구버전 인스턴스의 쓰기 차단 | 일부라도 전환되지 않았으면 중단 |
| **f** | **위가 모두 충족된 뒤에만** 쓰기 재개 | — |

**c 단계 점검 쿼리**

```sql
-- 상한 초과 사용자
SELECT ts.sale_event_id, s.held_by, COUNT(*)
  FROM seat_inventory s JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.status IN ('HELD','PAYING') AND s.held_by IS NOT NULL
 GROUP BY ts.sale_event_id, s.held_by HAVING COUNT(*) > 4;

-- 소유자를 알 수 없는 활성 좌석 (drift SQL 의 V-7c 와 같다)
SELECT id FROM seat_inventory
 WHERE status IN ('HELD','PAYING') AND held_by IS NULL;
```

### ★ 쓰기를 재개하지 않는 조건

- **c 또는 d 점검에서 한 행이라도 나온 경우**
- **일부 인스턴스가 e 의 버전으로 전환되지 않은 경우** — 구버전이 quota 없이 좌석을 바꾸면
  카운터가 즉시 어긋난다
- **b 에서 진행 중이던 트랜잭션의 종료를 확인하지 못한 경우**

이 상태로 quota 를 켜면 카운터가 실제와 어긋난 채 상한이 강제되어,
**정상 요청이 거부되거나(카운터 과대) 상한이 무력해진다(카운터 과소).**
원인을 해소한 뒤 **c 단계부터 다시** 한다.

> **현재 Task 2G-G 로는 활성화할 수 없다.** 요청 경로에서 quota 를 부르는 배선이 없다.
> 위 절차는 Task 2H 가 배선을 넣은 뒤에 쓰는 것이며, 이 문서는 **절차만 정한다** —
> 실제 backfill 실행, 쓰기 차단 기능, 배포 자동화는 만들지 않았다.

### ★ 비어 있는 카운터의 위험

카운터가 비어 있으면 **활성 좌석이 집계되지 않는다.** 테스트가 그 상태를 고정한다 —
좌석은 3석인데 카운터 행이 없다. 이 상태에서 요청 경로가 quota 를 강제하기 시작하면
**이미 3석을 잡은 사용자가 4석을 더 잡을 수 있다.**

그래서 **스키마 적용과 강제 활성화는 별개 절차**이며, 쓰기를 재개(**f**)하기 전에
**a~e** 가 모두 끝나야 한다. 이 Task 는 활성화 자체를 하지 않는다 (Task 2H).

---

## 5. 운영 저장소

```java
void                 ensureRow(SaleEventId, UserId)
QuotaAcquireOutcome  tryAcquire(SaleEventId, UserId, int seats)
QuotaReleaseOutcome  release(SaleEventId, UserId, int seats)
Optional<Integer>    lockRow(SaleEventId, UserId)
List<QuotaKey>       lockAll(List<QuotaKey>)
```

### 반환 계약

| 결과 | 의미 | 오류인가 |
|---|---|---|
| `ACQUIRED` | 이 요청이 몫을 확보했다 | — |
| `LIMIT_EXCEEDED` | 상한 초과 **또는 행 없음** | **아니다.** 429 로 매핑될 자리 |
| `RELEASED` | 카운터가 줄었다 | — |
| `REJECTED` | 행 없음 **또는 음수가 될 요청** | **drift 신호다.** 정상 흐름에 없다 |
| `lockRow` 빈 값 | **일치하는 행이 없다** (아래 주석 참고) | drift 신호 |
| `lockAll` → `QuotaRowMissingException` | 키 중 하나에 행이 없다 | drift 신호 |

**`LIMIT_EXCEEDED` 와 `REJECTED` 가 두 원인을 함께 뜻하는 이유** — 한 번의 조건부 UPDATE 로는
구분할 수 없고, 구분하려고 다시 조회하면 그 사이가 또 열린다. 구분이 필요하면 `lockRow` 를
먼저 부른다 — **잠금 순서상(quota → seat) 어차피 먼저다.**

`lockAll` 만 예외를 던지는 이유: 여러 행을 한꺼번에 다루는 호출자는 "어느 키가 없는지" 를
알아야 하고, 그것을 반환값으로 표현하면 호출부마다 분기가 생긴다.

#### ★ `lockRow` 의 빈 값은 "잠금이 없다" 는 뜻이 아니다

**일치하는 quota 행이 없다**는 뜻이다. REPEATABLE READ 에서 운영 `lockRow` 를 호출하고
`performance_schema.data_locks` 를 **잠금이 유지되는 동안** 관측한 결과다.

| 조회 | 반환 | 관측된 잠금 |
|---|---|---|
| 있는 키 `(9001, 30)` | 값 | `RECORD / X,REC_NOT_GAP` on `PRIMARY` `[9001, 30]` |
| **없는 키 `(9001, 20)`** | **빈 값** | **`RECORD / X,GAP`** on `PRIMARY` `[9001, 30]` |

**없는 키를 조회하면 다음 레코드 앞의 갭에 잠금이 잡힌다.** 그 사이에 다른 트랜잭션이
그 키를 INSERT 하려 하면 대기한다.

**관측한 범위는 REPEATABLE READ 하나다.** 위 표는 격리 수준을 명시하고 잠금이 유지되는
동안 관측한 결과이며, 이 표가 말하는 것은 그 조건에서의 동작뿐이다.
`READ COMMITTED` 는 **측정하지 않았다** — 갭 잠금 여부는 격리 수준에 좌우되므로
위 결과를 다른 격리 수준으로 옮겨 적으면 안 된다.

> 이전 문서에 "PK 전체를 등호로 지정하므로 REPEATABLE READ 에서 일반적으로 갭 잠금이
> 생기지 않는다" 고 적혀 있었다. **측정하지 않은 추측이었고, 측정해 보니 틀렸다.** 삭제했다.
> 같은 이유로 "없는 키의 갭 잠금을 측정하지 않았다" 는 문장도 더 이상 사실이 아니라
> 삭제했다 — 지금은 REPEATABLE READ 에서 측정된 값이 있다.

**판별 방법도 고정했다.** `LOCK_MODE` 는 쉼표로 구분된 플래그 목록이라
`contains("GAP")` 은 `X,REC_NOT_GAP` 까지 참으로 만들어 결론을 뒤집는다.
테스트는 **쉼표로 분리한 정확한 `GAP` 토큰**을 검사하고, 잠긴 키도
부분 문자열이 아니라 **전체 키 `(9001, 30)`** 로 비교한다.

계약은 반환값에 있다 — **빈 값이면 카운터가 없고, 자동 생성하지 않는다.**
**호출자가 잠금의 유무를 가정하지 않아야 한다.**

### 인자 검증

| 인자 | 계약 |
|---|---|
| `null` 식별자 | `NullPointerException` |
| `tryAcquire` 좌석 수 | **1~4.** 벗어나면 `IllegalArgumentException` |
| `release` 좌석 수 | **1~4.** 0 도 거부한다 |

**감소량 0 을 거부하는 이유.** 2G-F 의 스위퍼는 `affected_rows == 0` 이면 감소 SQL 자체를
보내지 않는다. 그 계약을 저장소가 강제한다 — 0 을 허용하면 "행이 없어서 0행" 과
"줄일 것이 없어서 0행" 이 같은 결과가 되어 **drift 를 숨긴다.**

상한(5 이상)을 인자 검증에서 막는 이유: 조건부 UPDATE 도 어차피 거부하지만 그것은
"상한 초과" 라는 **정상 결과**로 보고된다. 애초에 불가능한 요청은 **잘못된 입력**으로
구분하는 편이 호출부에서 읽기 쉽다.

### ★ 트랜잭션 계약 — "열려 있는가" 가 아니라 "참여하는가"

**이 저장소는 트랜잭션을 열지 않는다.** 모든 변경과 잠금은 호출자가 연 트랜잭션에 참여하며,
참여하지 않았으면 **SQL 을 보내기 전에** `IllegalStateException` 으로 거부한다.

독립 커밋을 만들면 quota 와 좌석이 서로 다른 시점에 확정된다. 좌석 선점이 실패해도 카운터만
늘어난 채 남고, 그 사용자는 **잡지도 못한 좌석 때문에 상한을 소진**한다.

**★ 통과해서는 안 되는 구성들.** 리뷰가 지적했고 테스트로 재현했다.

| 상황 | 통과시키면 일어나는 일 |
|---|---|
| **트랜잭션 없음** | 각 문장이 자기 혼자 커밋된다 |
| **다른 DataSource 의 트랜잭션만 활성** | quota 커넥션은 그 트랜잭션에 참여하지 않고 따로 확정된다 |
| **동기화 목적으로 바인딩만 된 커넥션** | Spring 이 커넥션을 리소스로 바인딩하지만 트랜잭션은 시작되지 않았다 |
| **★ 위 상태이면서 풀이 `autoCommit=false`** | 커넥션 반납 시 **롤백**된다. quota 는 성공한 것처럼 보이지만 사라지고 좌석만 커밋된다 |

마지막 줄이 리뷰가 실제 MySQL 로 재현한 것이다 — `tryAcquire` 가 `ACQUIRED` 를 돌려주고,
커밋 후 **HELD 좌석 1개 / quota 행 0개** 가 됐다.

### 판정을 직접 하지 않고 Spring 에게 묻는다

다음 신호는 **하나씩으로도, 합쳐서도** 실제 참여를 증명하지 못한다.

| 신호 | 왜 부족한가 |
|---|---|
| `isActualTransactionActive()` | 다른 DataSource 의 트랜잭션에도 참 |
| `hasResource(key)` | 동기화 목적 바인딩에서도 참 |
| `isSynchronizedWithTransaction()` | 위 바인딩에서 참으로 설정된다 |
| `Connection.getAutoCommit() == false` | **풀이 `autoCommit=false` 면 바인딩만 된 커넥션도 통과한다** |

정확한 신호는 `ConnectionHolder.isTransactionActive()` 인데 **protected** 다.
그런데 `DataSourceTransactionManager.isExistingTransaction()` 이 바로 그 값을 보고,
그 판정에는 **공개 API 로 도달할 수 있다** — `PROPAGATION_MANDATORY` 다.

```
isExistingTransaction(tx) == tx.hasConnectionHolder()
                             && tx.getConnectionHolder().isTransactionActive()
```

그래서 저장소는 **자기 DataSource 로 만든 `DataSourceTransactionManager` 에
`MANDATORY` 로 "이미 트랜잭션이 있는가" 를 묻는다.** 없으면
`IllegalTransactionStateException` 이 나고 **새 트랜잭션은 만들어지지 않는다** —
이 저장소가 최상위 트랜잭션을 열지 않는다는 계약은 그대로다.

**관리자를 주입받지 않고 직접 만드는 이유**는 배선 실수를 원천적으로 없애기 위해서다.
`doGetTransaction` 은 `getResource(dataSource)` 로 리소스를 찾으므로
**다른 인스턴스가 시작한 트랜잭션도 그대로 인식한다** — 조건은 리소스 동일성뿐이고
인스턴스 동일성은 요구하지 않는다.

**확인 뒤 정리.** 참여 중이면 반환되는 상태는 새 트랜잭션이 아니므로(`isNewTransaction()==false`)
커밋해도 DB 커밋이 아니라 정리일 뿐이다. 저장소는 rollback-only 일 때 롤백으로,
아니면 커밋으로 정리한다.

**이 분기의 실제 효과를 측정했다** (`QuotaTransactionEnlistmentTest` §6).

| 외부 상태 | probe 의 `isRollbackOnly()` | probe `commit()` | 외부 종료 |
|---|---|---|---|
| **global** rollback-only, `failEarlyOnGlobalRollbackOnly=false` (기본) | `true` | 예외 없음 | `UnexpectedRollbackException` |
| **global** rollback-only, `failEarlyOnGlobalRollbackOnly=true` | `true` | **`UnexpectedRollbackException`** | `UnexpectedRollbackException` |
| **local** (외부 `status.setRollbackOnly()`) | **`false`** | 예외 없음 | 예외 없음 |

읽어야 할 것은 세 가지다.

1. **`UnexpectedRollbackException` 은 probe 지점이 아니라 외부 트랜잭션 종료 시점에 난다.**
   기본 설정에서는 참여 상태의 커밋이 예외를 던지지 않는다 — 참여 상태의
   `processRollback` 이 `failEarlyOnGlobalRollbackOnly` 가 꺼져 있으면
   `unexpectedRollback` 을 다시 `false` 로 내리기 때문이다.
   **따라서 롤백 분기는 기본 설정에서 관측 가능한 차이를 만들지 않는다.**
   그래도 두는 이유는 `failEarly` 가 켜진 구성에서 커밋이 **저장소 호출 지점에서**
   예외를 던져 진짜 실패 원인을 가리기 때문이다 — 설정에 의존하지 않기 위한 방어다.
   이전 문서의 "커밋하면 `UnexpectedRollbackException` 이 나므로 롤백한다" 는
   **기본 설정에서는 사실이 아니었다.** 위 표로 고쳤다.
2. **local 과 global 은 다르다.** probe 가 보는 것은 JDBC 리소스(커넥션 홀더)의
   **global** 표시다. 외부 `TransactionStatus` 의 **local** 표시는 보이지 않아
   커밋 분기를 타지만, 참여 상태의 커밋은 물리 커넥션을 건드리지 않으므로 무해하다.
3. **가드는 rollback-only 를 지우지도, 독립 커밋을 만들지도 않는다.**
   rollback-only 상태에서 quota 를 변경하고 호출자가 예외를 던지지 않아도
   커밋은 `UnexpectedRollbackException` 으로 끝나고 행은 남지 않는다.

**정상 참여로 인정하는 것** — 같은 DataSource 를 관리하는 **다른 관리자 인스턴스**,
그리고 `TransactionAwareDataSourceProxy` 를 씌운 구성. 조건은 인스턴스 동일성이 아니라
**리소스 동일성**이다.

거부를 SQL **앞에** 두는 이유는, DB 를 건드리고 나서 알리면 이미 늦기 때문이다.
테스트가 "거부된 호출은 quota SQL 을 보내지 않는다" 를 확인하는데,
**관측 방법의 한계를 정확히 적어 둔다.** `RecordingDataSource` 는 `Connection` 을
동적 프록시로 감싸 `prepareStatement`·`prepareCall`·`nativeSQL` 에 **전달된 SQL 문자열**을
기록한다. 즉 관측하는 것은 **PreparedStatement 생성 시점**이지 `execute` 호출이 아니다.
따라서 이 테스트가 보이는 것은 "가드가 SQL 을 만들기 전에 거부했다" 이고,
"execute 가 호출되지 않았다" 를 직접 관측한 것은 아니다.
가드가 SQL 생성 이전 단계에 있으므로 이 관측으로 충분하지만,
**둘을 같은 말로 쓰지 않는다.** DB 상태 어서션(행이 남지 않았다)이 이를 보완한다.
커넥션 상태를 읽지 못하면 **거부한다**(fail closed) — 판단할 수 없을 때 통과시키면
가드가 있는 의미가 없다.

### `ensureRow` 가 값을 보존해야 하는 이유

`ON DUPLICATE KEY UPDATE held_seats = held_seats` 는 값을 바꾸지 않는 no-op 갱신이다.
여기서 0 으로 되돌리면 **이미 잡은 좌석의 몫이 사라진다.** 테스트가 3석을 확보한 뒤
재확보해도 3이 유지되는지 확인한다.

`INSERT IGNORE` 를 쓰지 않는 이유는 2G-A 가 실측했다 — 중복 키뿐 아니라 **CHECK 위반이나
FK 위반 같은 다른 오류까지 경고로 낮춰 삼킨다.**

---

## 6. drift 탐지 SQL

`load-test/verify/user_hold_quota_drift.sql` 에 세 문장을 뒀다.
INVARIANTS.md 의 다른 검증 쿼리와 같은 규약이다 — **하나라도 행을 반환하면 CI 실패.**

| 쿼리 | 탐지 대상 |
|---|---|
| **V-7a** | 카운터 과대 / 과소 / **활성 좌석 없는 카운터** |
| **V-7b** | **활성 좌석은 있는데 카운터 행이 없음** ★ 가장 위험 |
| **V-7c** | `held_by` NULL 인 활성 좌석 |

- **정상 상태를 보고하지 않는다.** 카운터 0 이고 활성 좌석도 0 인 사용자는 drift 가 아니다 —
  좌석을 다 놓아준 뒤의 정상 상태이며 행은 0 으로 남긴다. 테스트가 이것을 고정한다.
- **V-7c 는 앞의 두 쿼리로 보이지 않는다.** 그 좌석이 어느 집계에도 들어가지 않아
  카운터와 집계가 "맞아 보인다". 따로 세야 한다.
- MySQL 에 `FULL OUTER JOIN` 이 없어 두 방향을 각각 본다.
- 회차는 `seat_inventory → train_schedule` 조인으로 얻는다 (2G-E-B1 §8 의 정규화 결정).

### ★ 자동 보정을 넣지 않았다

좌석과 카운터 중 무엇이 진실의 원천인지 근거 없이 정할 수 없다. 특히 **확정 경로의 quota
감소가 아직 없어서**(2G-C 미해결) 카운터가 "옳게" 클 수 있다. 그 상태에서 좌석 집계에
맞추면 진짜 문제를 지운다. 기각 근거는 2G-F §10 에 있다.

**테스트가 SQL 파일을 직접 읽어 실행한다.** 자바 상수로 복사해 두면 운영 파일이 바뀌어도
검증이 눈치채지 못한다. 보정문(`UPDATE`/`DELETE`/`INSERT`)이 없다는 것도 테스트가 확인한다.

---

## 7. 검증 결과

실제 MySQL 8.4 Testcontainers, H2 없음 (규칙 26).

| 테스트 | 다루는 것 |
|---|---|
| `UserHoldQuotaSchemaMigrationTest` | fresh 마이그레이션, 기존 데이터 upgrade, FK·CHECK·PK |
| `JdbcUserHoldQuotaRepositoryTest` | 저장소 계약, 트랜잭션 경계, 동시성, 인자 검증 |
| `UserHoldQuotaDriftDetectionSqlTest` | 운영 SQL 파일을 그대로 읽어 실행 |
| `QuotaTransactionEnlistmentTest` | **트랜잭션 참여 검증** — 다른 DataSource·바인딩만 된 커넥션·`autoCommit=false` 풀·프록시·SQL 미발송 관측·**커밋/롤백 원자성**·**rollback-only 정리** |
| `QuotaLockRowGapLockTest` | **`lockRow` 의 실제 잠금 관측** — 있는 키/없는 키, **`GAP` 플래그 판별** |

### 검증한 것

- 빈 스키마에서 V1~V6 이 적용되고 카운터는 비어 있다
- PK 가 `(sale_event_id, user_id)` — 잠금 키 순서와 같다
- `sale_event` FK, `held_seats` NOT NULL·기본값 0, CHECK 0~4, 같은 키 중복 불가
- 시간 컬럼이 `DATETIME(3)`
- **활성 좌석이 있어도 V6 은 적용되고 좌석을 건드리지 않는다**
- ★ **카운터가 비어 있어 활성 좌석이 집계되지 않는다** (강제 활성화 전 위험)
- ★ **상한을 넘는 사용자가 있으면 backfill 이 CHECK 위반으로 실패하고 부분 반영도 없다**
- 상한 안의 데이터는 운영자가 명시적으로 backfill 할 수 있다
- 강제 활성화 전 점검 쿼리가 상한 초과를 찾아낸다
- `ensureRow` 가 **기존 값을 보존**하고, 반복 호출해도 행이 하나다
- 확보: 상한 안 성공 / 초과 거부(값 불변) / 정확히 상한까지 / **없는 행에는 확보 안 됨**
- 감소: 보유량 안 감소 / **음수가 될 요청 거부** / **없는 행 거부·생성 안 함** / 전량 감소 후 0
- 잠금: 있는 행의 값 반환 / 없는 행은 빈 값이고 행을 만들지 않음
- ★ **트랜잭션 없는 변경·잠금 5종이 모두 거부되고 DB 를 건드리지 않는다**
- ★ **다른 DataSource 의 트랜잭션만 활성이면 5종이 모두 거부된다** (RED 로 먼저 재현)
- ★ **동기화 목적으로 바인딩만 된 커넥션도 거부된다** — `autoCommit=true`·`false` 모두 (RED 로 먼저 재현)
- ★ **`autoCommit=false` 인 다른 풀에서 좌석만 커밋되는 상황이 차단된다** (RED 로 먼저 재현)
- ★ **거부된 호출이 quota 변경·잠금 SQL 을 실행하지 않는다** — 실행된 문장을 직접 관측.
  사전 바인딩용 `SELECT 1` 은 관측 구간에서 제외한다
- **`autoCommit=false` 풀이라도 그 풀의 관리자가 트랜잭션을 시작했으면 정상 참여**하고
  롤백도 되돌아간다
- **같은 DataSource 의 다른 관리자 인스턴스**로도 정상 동작한다
- **`TransactionAwareDataSourceProxy` 구성**도 정상 동작한다
- ★ **외부 롤백이면 quota 변경도 복구된다**
- ★ **좌석 선점 실패가 전파되면 quota 증가도 롤백된다**
- 성공하면 quota 와 좌석이 함께 커밋된다
- ★ **동시 최초 요청 16개에서도 행이 하나만 생성된다**
- ★ **동시 3석 요청 두 개 중 하나만 성공하고 최종 값은 3**
- 동시 요청 16개에서도 상한 4를 넘지 않는다
- **다른 사용자·다른 회차의 quota 는 독립**이다
- `QuotaKey` 정렬과 `lockAll` 의 오름차순 잠금, 없는 행 알림
- 인자 검증: null / 1~4 범위 / **감소량 0 거부** / 트랜잭션 검사가 먼저
- drift: 정상 상태 4종 무보고(**카운터 0 + 좌석 0 포함**), 방향별 5종 탐지, 읽기 전용·멱등
- ★ **`lockRow` 의 실제 잠금** — 있는 키는 `PRIMARY` 위 `X,REC_NOT_GAP`,
  **없는 키 `(9001,20)` 는 다음 레코드 `(9001,30)` 위 `X,GAP`**.
  REPEATABLE READ 명시, 잠금 유지 중 `performance_schema.data_locks` 를
  테이블·`LOCK_TYPE`·스레드로 좁혀 관측. 인덱스명·**전체 키**·`LOCK_MODE` 를 함께 어서션한다.
  커밋 뒤에는 잠금이 남지 않는다
- ★ **`GAP` 플래그 판별** — `X,GAP` 은 참, **`X,REC_NOT_GAP` 은 거짓**.
  쉼표 토큰 비교이며, 전체 키 비교가 `(9001,3)`·`(900,30)` 을 걸러낸다
- ★ **quota 와 좌석의 커밋·롤백 원자성** — 같은 DataSource 의 외부 트랜잭션에서
  운영 `JdbcUserHoldQuotaRepository` 와 `JdbcMultiSeatHoldRepository` 를 함께 쓴다.
  트랜잭션 안의 반환값(`ACQUIRED`)과 종료 후 DB 상태를 모두 확인하고,
  **새 행 생성**(롤백 시 행 자체가 사라짐)과 **기존 카운터 증가**(원래 값으로 복귀,
  행은 남음)를 구분한다. 프록시 구성에서도 같다
- ★ **rollback-only 상태의 참여 정리** — 위 표의 세 경우를 실제 Spring 동작으로 재현했다.
  rollback-only 는 안쪽 참여 트랜잭션을 실패시켜 만들며 **reflection 이나
  private 필드 조작을 쓰지 않는다**

동시성 테스트는 `CountDownLatch` 배리어 + timeout + `Future.get()` 결과 회수를 쓴다
(`Thread.sleep` 없음, 규칙 25).

### 검증하지 않은 것

- **요청 경로 전체의 I-12 강제** — 서비스 배선이 없다 (Task 2H)
- **확정·해제 경로의 quota 감소** — 2G-C 의 미해결 항목 그대로다
- **만료 스위퍼와의 실제 연동** — 2G-F 는 테스트 전용 스위퍼로만 검증했다
- **대규모 경합에서의 카운터 행 병목** — 같은 사용자의 요청은 한 행에서 직렬화된다.
  그 비용을 측정하지 않았다
- **`held_by` NULL 의 발생 원인** — 여전히 미상이다
- **운영 backfill 절차의 실제 수행** — 절차만 정했다. 쓰기 차단 기능·배포 자동화는 만들지 않았다
- **`READ COMMITTED` 격리에서의 `lockRow` 잠금** — 갭 잠금 여부가 격리 수준에 좌우되는데
  **REPEATABLE READ 만 관측했다.** 위 잠금 표를 다른 격리 수준으로 옮겨 적으면 안 된다
- **`LazyConnectionDataSourceProxy` 같은 다른 프록시 구성** — 리소스 키 정규화가
  벗기지 못해 거부된다. 거부되는 편이 안전하지만 실제로 시험하지는 않았다
- **`execute` 호출 자체의 미발생** — SQL 미발송은 `PreparedStatement` **생성 시점**의
  기록으로 관측했다. `execute` 호출을 직접 관측하지는 않았다
- **`failEarlyOnGlobalRollbackOnly=true` 구성의 운영 사용** — 그 설정에서 롤백 분기가
  필요하다는 것은 테스트로 고정했지만, 운영 배선은 기본 설정을 쓴다
- **갭 잠금이 실제 경합에 주는 영향** — 없는 키 조회가 갭을 잠근다는 사실은 측정했지만,
  첫 요청이 몰릴 때 그 대기가 얼마나 되는지는 측정하지 않았다

---

## 8. 남은 위험

| 항목 | 내용 |
|---|---|
| **★ I-12 는 아직 요청 경로에 강제되지 않는다** | 저장소만으로는 아무 일도 일어나지 않는다. 선점 유스케이스 배선이 Task 2H 다 |
| **비어 있는 카운터로 강제를 켜면 상한이 무력하다** | §4 의 **c(backfill)·d(drift 0행)** 를 건너뛰면 기존 활성 좌석이 집계되지 않는다 |
| **확정·해제 경로 감소 부재** | 만료만 연동하면 카운터가 실제보다 계속 커진다. 이 때문에 drift 자동 보정을 기각했다 |
| **`held_by` NULL 좌석** | 어느 카운터에도 집계되지 않고 스위퍼도 회수하지 못한다. 원인 미상 |
| **카운터 행 병목 미측정** | 같은 사용자의 동시 요청은 한 행에서 직렬화된다. 인기 회차에서의 대기 시간을 재지 않았다 |
| **drift 수동 보정 절차 미정** | 탐지만 있다. 누가 어떤 권한으로 무엇을 고치는지 정해지지 않았다 |
| **활성화 절차가 문서뿐** | §4 의 쓰기 중단·인스턴스 통제를 강제하는 수단이 없다. 사람이 지켜야 한다 |
| **참여 확인의 비용** | 호출마다 `getTransaction`/`commit` 이 한 번씩 붙는다. 참여 상태에서는 DB 왕복이 없지만 측정하지는 않았다 |
| **없는 키 조회의 갭 잠금** | `lockRow` 가 빈 값을 돌려줘도 갭 잠금이 생긴다. 그 사이 같은 키 INSERT 는 대기한다 — 첫 요청 경로가 몰리면 대기가 생길 수 있고 측정하지 않았다 |
| **상한 초과 데이터의 정책 미정** | §4 **c** 에서 중단하라고만 정했다. 어느 좌석을 빼야 하는지는 결정되지 않았다 |

---

## 9. Task 2H 로 넘길 사항

1. 선점 유스케이스 — **quota 확보 → 좌석 선점을 한 트랜잭션에서** (잠금 순서 quota → seat)
2. 확정·만료·해제 세 이탈 경로의 quota 감소 연동
3. 만료 스위퍼 배선 — 2G-F 의 8가지 계약을 운영 코드로
4. `@Scheduled` 와 다중 스위퍼 정책 (ShedLock 은 측정 후 판단)
5. 예외의 HTTP 매핑 — `LIMIT_EXCEEDED` → 429 + `Retry-After` (규칙 22)
6. `Idempotency-Key` 저장소 (규칙 14~17)
7. 메트릭·감사 로그 (규칙 32·35)
8. drift 탐지를 CI·운영 점검에 편입

---

## 10. 재현 명령

```bash
./gradlew :modules:reservation-infra:test \
  --tests "*UserHoldQuotaSchemaMigrationTest" \
  --tests "*JdbcUserHoldQuotaRepositoryTest" \
  --tests "*UserHoldQuotaDriftDetectionSqlTest" \
  --tests "*QuotaTransactionEnlistmentTest" \
  --tests "*QuotaLockRowGapLockTest"
```

잠금 관측 테스트는 `performance_schema` 를 읽으므로 컨테이너 root 계정을 쓴다.

Docker 가 실행 중이어야 한다. 마이그레이션 절차 테스트는 공유 컨테이너에 별도 스키마
(`railgate_mig_2gg`)를 만들어 처음부터 돌리고 끝나면 지운다.
