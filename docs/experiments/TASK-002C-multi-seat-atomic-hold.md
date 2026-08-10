# 실험 — 다좌석 원자 선점 (I-9)

> TASK-002C · 2026-08-02
> 관련: [INVARIANTS.md](../INVARIANTS.md) I-9·I-12, [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.3,
> [CLAUDE.md](../../CLAUDE.md) 규칙 2·3·11·27
> 선행: [TASK-002A](TASK-002A-seat-hold-race.md) · [TASK-002B](TASK-002B-seat-confirm-race.md)

## 목적

Task 2A/2B가 **좌석 하나**의 전이를 다뤘다면, 여기서는 **여러 좌석을 묶은 요청**을 다룬다.

I-9는 "여러 좌석 예약은 전부 선점되거나 전부 실패한다"이다. 새로 등장하는 실패 양식은 두 가지다.

| 실패 양식 | 단일 좌석에는 없던 이유 |
|---|---|
| **부분 성공** | 좌석 하나는 성공 아니면 실패뿐이다. 여러 좌석은 "3석 중 2석만 잡힌" 상태가 가능하다 |
| **데드락** | 행을 하나만 잠그면 순환 대기가 성립하지 않는다 |

> **검증 범위 전제.** 이 문서가 다루는 것은 **저장소가 단독으로 호출되는 범위**다.
> 외부 트랜잭션에 참여하는 경우의 동작은 검증되지 않았으며, 그 위험은 §4에 기록했다.
> "I-9 확보"라는 서술은 모두 이 전제 안에서 읽어야 한다.

---

## 1. 구조적 논증 — 왜 단일 벌크 UPDATE인가

### 개별 UPDATE 반복이 깨지는 이유

각 좌석마다 조건부 UPDATE를 반복하면, **각 문장은 개별적으로 완전히 올바르다.** Task 2A가 검증한 바로 그 CAS다. 그런데도 요청 전체로는 깨진다.

```
UPDATE ... WHERE id=1 AND status='AVAILABLE'   → affected=1, 즉시 커밋
UPDATE ... WHERE id=2 AND status='AVAILABLE'   → affected=0 (남의 것)
                                                → 좌석 1은 이미 잡힌 채로 남는다
```

**단일 좌석 수준의 정확성만으로는 I-9를 얻을 수 없다.** 이것이 이 Task의 핵심이며, 트랜잭션이 없으면 각 문장이 즉시 확정되어 되돌릴 수단이 사라진다 (CLAUDE.md 규칙 11).

### 벌크 UPDATE + affected_rows

```sql
UPDATE seat_inventory FORCE INDEX (PRIMARY)
   SET status     = 'HELD',
       hold_id    = :holdId,
       held_by    = :userId,
       held_at    = NOW(3),
       expires_at = DATE_ADD(NOW(3), INTERVAL :holdSeconds SECOND),
       version    = version + 1
 WHERE id     IN (:seatIds)
   AND status = 'AVAILABLE'
```

`affected_rows`가 요청 좌석 수와 같으면 전부 성공, 다르면 하나 이상이 이미 남의 것이다. **판정에 사후 SELECT를 쓰지 않는다** — 재조회는 또 하나의 창을 만들 뿐이다.

`IN` 절은 `NamedParameterJdbcTemplate`이 바인딩한다. 값을 문자열로 이어 붙이면 SQL 인젝션 표면이 생기고 조립 코드를 직접 관리해야 한다.

> 다만 컬렉션 파라미터는 실행 시 **좌석 수만큼의 `?`로 확장**되므로, 최종 프리페어드 스테이트먼트의 형태는 좌석 수에 따라 달라진다. 여기서 얻는 것은 **인젝션 방지와 안전한 바인딩**이지 statement 형태의 동일성이 아니다. 좌석 수가 1~4석으로 제한되어 있어 형태 차이 자체는 문제가 되지 않는다.

### 왜 롤백이 필요한가

**벌크 UPDATE만으로는 부족하다.** 이 문장은 "가능한 것만 갱신하고 몇 개 했는지"를 알려준다. 3석 중 2석이 가능하면 **그 2석을 실제로 `HELD`로 바꾼 뒤** `affected_rows = 2`를 돌려준다.

여기서 멈추면 **실패를 응답받은 요청이 2석을 점유한 채 남는다.** 개별 UPDATE 반복과 같은 결과다. 트랜잭션 롤백이 그것을 되돌린다.

```java
return transactionTemplate.execute(status -> {
    int affectedRows = jdbc.update(HOLD_ALL_SQL, params);
    if (affectedRows == requested.value()) {
        return MultiSeatHoldOutcome.HELD;
    }
    status.setRollbackOnly();                    // local rollback-only
    return MultiSeatHoldOutcome.SEAT_UNAVAILABLE;
});
```

**외부 트랜잭션이 없는 독립 호출**에서는 여기서 트랜잭션이 새로 생성되므로 `setRollbackOnly()`가 **local** rollback-only로 표시되고, `TransactionTemplate`이 커밋 대신 롤백한 뒤 예외 없이 반환값을 돌려준다. 이 동작은 테스트로 확인했다(`롤백은_예외가_아니라_결과값으로_보고된다`).

> 이는 **모든 호출 맥락에 일반화되지 않는다.** 외부 트랜잭션에 참여하면 전역 rollback-only가 되어 외부 커밋 시 `UnexpectedRollbackException`이 발생할 수 있다. §4 참고.

### 왜 PK 오름차순과 FORCE INDEX인가

여러 행을 잠그면 순환 대기가 가능한 구조가 된다.

```
A: 좌석 1 잠금 → 좌석 2 대기
B: 좌석 2 잠금 → 좌석 1 대기      → 교착
```

**여기서 주의할 점이 있다.** 실제 행 접근 순서는 `IN` 파라미터 배열의 순서만으로 정해지지 않는다. **MySQL의 실행 계획과 선택된 인덱스가 함께 영향을 준다.** 정렬된 ID를 전달하는 것만으로는 접근 순서가 고정되지 않는다.

그래서 두 가지를 함께 쓴다.

| 수단 | 역할 |
|---|---|
| `SeatHoldPolicy.lockOrder` | 정렬된 ID를 전달한다. 도메인이 이미 제공하던 함수 (CLAUDE.md 규칙 2) |
| `FORCE INDEX (PRIMARY)` | 옵티마이저가 다른 인덱스를 고를 여지를 없앤다. `status = 'AVAILABLE'` 조건 때문에 그 여지가 실제로 있다 (규칙 3) |

`EXPLAIN` 어서션 테스트가 계획이 PRIMARY를 벗어나는 것을 감지한다. 이것이 **결정적 테스트** 층이다.

원본 리스트는 정렬하지 않는다. in-place 정렬은 호출자의 컬렉션을 조용히 바꾸고, `List.of(...)` 같은 불변 리스트에서는 예외가 난다.

> **주장 범위 (CLAUDE.md 규칙 30).**
> I-9의 전부-또는-전무는 벌크 UPDATE + `affected_rows` 비교 + 실패 시 같은 트랜잭션 롤백으로 **구조적으로 논증된다.**
> 반면 **데드락 부재는 구조적으로 논증하지 않는다.** 잠금 순서 고정은 위 두 수단의 조합에 의존하며,
> 동시성 테스트 결과는 해당 환경과 실행 횟수에서 데드락이 관측되지 않았다는 **통계적 보조 근거**일 뿐이다.

---

## 2. 실패 재현 — 개별 UPDATE 반복

**이 재현에는 동시성이 필요 없다.** 결함이 경쟁 조건이 아니라 트랜잭션 부재이기 때문이다. 요청이 하나뿐이어도 깨진다. 배리어도 지연 주입도 쓰지 않으며 실행할 때마다 같은 결과가 나온다.

**설정:** 좌석 1A·2A·3A 중 **2A를 다른 사용자가 먼저 점유**. 요청자가 세 좌석을 함께 요청.

| | 응답 | 요청자가 점유한 좌석 | 판정 |
|---|---|---|---|
| 개별 UPDATE 반복 | `SEAT_UNAVAILABLE` | **1석 (1A)** | ❌ 부분 선점 |
| 벌크 UPDATE + 롤백 | `SEAT_UNAVAILABLE` | **0석** | ✅ |

개별 UPDATE 반복에서 요청자는 **실패를 응답받고도 1석을 점유한 채 남는다.** 3석 중 1석만 가진 모순된 상태이며, 3A는 도달조차 못 했다.

두 구현 모두 **남의 홀드는 건드리지 않는다.** 차이는 "남의 것을 침범하는가"가 아니라 **"내 실패를 되돌리는가"** 다.

---

## 3. 동시성 측정

환경은 이전과 동일하다. MySQL 8.4 (Testcontainers), HikariCP max 64, `innodb_lock_wait_timeout = 3`, Java 21 가상 스레드, `CountDownLatch` 동시 출발.

### 겹치는 좌석 요청 200건

8석 풀에서 2~4석씩 겹치게 요청.

| 지표 | 값 |
|---|---|
| 시스템 오류 / deadlock / lock timeout | **0건** |
| **부분 선점** | **0건** |
| 소요 | 0.18초 |

검증 방식: `hold_id`별로 `HELD` 좌석 수를 세어 **요청 좌석 수와 정확히 일치**하는지 확인한다. DB에 있는 모든 홀드가 성공한 요청이어야 하고, 그 좌석 수가 요청 크기와 달라서는 안 된다.

### 정순/역순 동시 요청 200건

같은 4석 집합을 절반은 `[0,1,2,3]`, 절반은 `[3,2,1,0]` 순서로 요청.
**잠금 순서가 일관되지 않으면 교착 위험이 있는 입력**이며, 그 위험을 겨냥해 만든 시나리오다.

| 지표 | 값 |
|---|---|
| 데드락(1213) / 잠금 대기 초과(1205) | **0건 관측** |
| 성공 | **1건** (나머지 199건 정상 실패) |
| 소요 | 0.16초 |

**이 결과는 데드락 부재의 증명이 아니다.** 이 환경(MySQL 8.4, 커넥션 64, 200건, 1회 실행)에서 관측되지 않았다는 보조 근거다. 잠금 순서를 실제로 고정하는 것은 정렬된 ID 전달과 `FORCE INDEX (PRIMARY)`의 조합이며, 그중 후자가 유지되는지는 `EXPLAIN` 테스트가 감시한다.

### 좌석 수 정합성 200건

3석짜리 요청 200건. 최종 `HELD` 좌석 수가 **성공한 요청 수 × 3**과 정확히 일치한다. 부분 선점이 하나라도 있으면 이 등식이 깨진다.

### 승자 일치 100건

같은 2석을 100건이 요청 → 성공 1건. DB의 `hold_id`·`held_by`가 성공 응답을 받은 요청과 일치.

### 기능 검증

`reservation-infra` **97 tests, 0 failures** (Task 2B 시점 66 → +31).

| 검증 | 결과 |
|---|---|
| 1석 / 2석 / 4석 전체 성공 | 통과 |
| 성공한 모든 좌석에 동일 `hold_id`·`held_by` | 통과 |
| 0석 / 5석 요청 | 거부 (`SeatCount` 재사용) |
| 중복 `SeatId` | 거부 |
| 입력 역순 요청 | 정상 성공 |
| 원본 리스트 불변 | 통과 |
| 불변 리스트(`List.of`) 전달 | 통과 |
| `EXPLAIN` → `key = PRIMARY` | 통과 |
| 한 좌석이라도 `HELD`면 전체 실패 | 통과 |
| 실패 시 이미 갱신된 행도 롤백 | 통과 |
| 실패 후 남의 홀드 불변 | 통과 |
| 존재하지 않는 좌석이 섞이면 전체 실패 | 통과 |

---

## 4. ⚠️ 별도로 남아 있는 것

### I-12 1인당 좌석 상한 — 이번 Task에서 의도적으로 제외

**증가만 구현하면 카운터가 영구히 부풀어 오른다.**

`user_hold_quota` 카운터 행은 조건부 UPDATE로 검사와 증가를 원자화한다(CLAUDE.md 규칙 8). 그런데 지금 시스템에는 **감소 경로가 하나도 없다.**

| 이탈 경로 | 현재 상태 |
|---|---|
| `PAYING → SOLD` 확정 | 구현됨. **그러나 quota를 감소시키지 않는다** |
| 자발적 해제(release) | 미구현 *(→ [TASK-002E](TASK-002E-seat-hold-release.md)에서 저장소 CAS 구현됨)* |
| 만료(expiry) / 스위퍼 | 미구현 |

증가만 넣으면 사용자가 4석을 잡고 확정한 뒤에도 카운터가 4로 남아 **영원히 추가 예매를 할 수 없게 된다.** 그 상태를 되돌릴 방법도 없다.

따라서 I-12는 **진입(HELD/PAYING)과 이탈(SOLD/release/expiry)을 함께** 구현해야 한다. 스위퍼와 해제 경로가 생기는 시점의 Task다.

### 규칙 32 감사 로그 — 미해결

`seat_state_log` 테이블과 기록 로직이 여전히 없다. `actor`/`reason`/`traceId`를 전달할 앱 계층이 없어 임의 값으로 채우지 않았다.

다좌석 선점에서는 이 부재가 더 아프다. **롤백된 요청은 흔적을 남기지 않는다** — 어떤 요청이 어떤 좌석을 시도했다 실패했는지 사후에 알 수 없다. 경합 분석에 필요한 데이터가 통째로 없는 셈이다.

### ★ 외부 트랜잭션 참여 시 동작 — 미검증

`JdbcMultiSeatHoldRepository`는 자체 `DataSourceTransactionManager`로 트랜잭션을 열고, 부분 실패 시 `status.setRollbackOnly()`로 되돌린다.

**검증된 것은 "외부 트랜잭션이 없는 독립 호출" 범위뿐이다.** 그 범위에서는 트랜잭션이 새로 생성되므로 local rollback-only가 되고, `TransactionTemplate`이 롤백한 뒤 예외 없이 `SEAT_UNAVAILABLE`을 돌려준다.

**외부 트랜잭션에 참여하면 달라질 수 있다.** 애플리케이션 서비스가 같은 `DataSource` 위에서 트랜잭션을 먼저 열어둔 채 이 메서드를 호출하면, 여기서는 새 트랜잭션이 생기지 않고 기존 트랜잭션에 **참여**한다. 그때의 `setRollbackOnly()`는 **외부 트랜잭션 전체를 rollback-only로 만들고**, 외부 커밋 시점에 `UnexpectedRollbackException`이 발생할 수 있다.

| 필요한 조치 | 시점 |
|---|---|
| 트랜잭션 경계를 **애플리케이션 계층이 소유**하도록 재검토 | 앱 배선 Task |
| 외부 트랜잭션 참여 상황의 **통합 테스트** | I-12 카운터·감사 로그를 같은 트랜잭션으로 묶기 **전에** |

I-12 카운터 증감이나 `seat_state_log` 기록은 좌석 UPDATE와 원자적으로 묶여야 하는 작업이다. 그 순간 이 저장소는 반드시 외부 트랜잭션에 참여하게 되므로, **그 전에 위 통합 테스트가 있어야 한다.**

> 이번 Task에서는 트랜잭션 구조를 리팩터링하지 않고 위험만 기록한다.

### ★ `innodb_lock_wait_timeout` — 테스트 환경에만 적용됨

이 문서의 모든 측정은 **테스트 세션에 `innodb_lock_wait_timeout = 3`이 적용된 환경**의 결과다. `MySqlTestSupport`가 HikariCP `connectionInitSql`로 설정한다.

**이 설정이 운영 경로에 자동으로 적용된다고 주장할 수 없다.** `reservation-infra`가 아직 앱에 배선되지 않았으므로 **운영 DataSource 설정 자체가 존재하지 않는다.** 저장소 코드는 이 값을 강제할 수단이 없다.

다좌석은 단일 좌석보다 이 설정에 더 민감할 수 있다. **여러 행을 하나의 트랜잭션에서 갱신하므로 단일 문장 autocommit 경로보다 잠금 범위와 경합 영향이 커질 수 있기 때문이다.** 실제 잠금 보유 시간은 환경과 경합 정도에 따라 달라지며 **별도 측정이 필요하다.** 이 문서에는 그 측정이 없다.

| 필요한 조치 | 시점 |
|---|---|
| HikariCP 등 커넥션 초기화 설정으로 **모든 세션에 적용** | 앱 배선 Task |
| 실제 새 커넥션의 `@@session.innodb_lock_wait_timeout` 값을 확인하는 **통합 테스트** | 앱 배선 Task |

> 아직 존재하지 않는 운영 DataSource 설정을 이번 Task에서 임의로 추가하지 않았다.

### 규칙 35 Micrometer 메트릭 — 미구현

선점 성공/실패, 경합률, 롤백 횟수를 계측하는 메트릭이 없다. 부분 선점 여부는 테스트로만 확인할 수 있고 운영 중에는 관측 수단이 없다.

메트릭은 **앱 진입점과 애플리케이션 서비스가 생기는 배선 Task**에서 추가한다. 지금은 계측을 붙일 요청 경계 자체가 없다.

### 그 밖

| 항목 | 상태 |
|---|---|
| I-11 만료 스위퍼 경쟁 | 미검증. 스위퍼 자체가 없다 *(→ [TASK-002D](TASK-002D-seat-expiry-sweeper.md)에서 구현·검증됨)* |
| I-15 결제 승인 검증 | 미구현. `payment` 테이블·PG 연동 없음 |
| 멱등성 키 | 미구현. 재요청에 최초 응답을 돌려주는 것은 별개 인프라 |
| REST API / 앱 배선 | 미구현 |

`JdbcSeatHoldRepository`(단일 좌석)와 `JdbcMultiSeatHoldRepository`(다좌석)가 공존한다. **아직 앱에 배선된 것이 없으므로 어느 쪽이 운영 진입점인지는 정해지지 않았다.** 전자는 Task 2A의 단일 좌석 CAS 검증 자산으로 유지한다.

---

## 5. 재현 명령

```bash
./gradlew :modules:reservation-infra:test --tests "*MultiSeatHoldPartialFailureTest"
./gradlew :modules:reservation-infra:test --tests "*MultiSeatHoldConcurrencyTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcMultiSeatHoldRepositoryTest"
```

Docker가 실행 중이어야 한다.
