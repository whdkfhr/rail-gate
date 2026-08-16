# 실험 — 트랜잭션 경계 소유권 정리 (다좌석 선점)

> TASK-002F · 2026-08-11
> 관련: [INVARIANTS.md](../INVARIANTS.md) I-9·I-12, [CLAUDE.md](../../CLAUDE.md) 규칙 1·2·3·10·11·21·26
> 선행: [2C](TASK-002C-multi-seat-atomic-hold.md) · [2D](TASK-002D-seat-expiry-sweeper.md) · [2E](TASK-002E-seat-hold-release.md)

## 1. 목적

Task 2C 가 "미검증 위험" 으로만 기록했던 항목을 **실제로 재현하고 구조를 고친다.**

I-12 를 구현하려면 좌석 UPDATE 와 quota 증감이 **하나의 트랜잭션**으로 묶여야 한다.
그 순간 저장소는 반드시 외부 트랜잭션에 참여하게 되는데, 지금 구조로는 그때 깨진다.
**그래서 quota 보다 경계 정리가 먼저다.** 이번 Task 는 I-12 를 구현하지 않는다.

---

## 2. 기존 구현 구조

```java
public MultiSeatHoldOutcome holdAll(...) {
    ...
    return transactionTemplate.execute(status -> {          // 저장소가 트랜잭션을 소유
        int affectedRows = jdbc.update(HOLD_ALL_SQL, params);
        if (affectedRows == requested.value()) {
            return MultiSeatHoldOutcome.HELD;
        }
        status.setRollbackOnly();                            // ← 문제의 지점
        return MultiSeatHoldOutcome.SEAT_UNAVAILABLE;
    });
}
```

- 내부에서 `DataSourceTransactionManager` 와 `TransactionTemplate` 을 직접 만든다.
- 부분 실패 시 `setRollbackOnly()` 로 되돌리고 **결과값**으로 실패를 보고한다.

**독립 호출에서는 이게 동작한다.** 트랜잭션이 새로 생성되므로 `setRollbackOnly()` 는
*local* rollback-only 가 되고, `TransactionTemplate` 이 롤백한 뒤 예외 없이 값을 돌려준다.

---

## 3. `UnexpectedRollbackException` 재현 조건

Spring 의 `setRollbackOnly()` 는 **트랜잭션이 새로 생성됐는지 참여했는지에 따라 의미가 다르다.**

| 상황 | `setRollbackOnly()` 의 효과 | 결과 |
|---|---|---|
| 트랜잭션이 새로 생성됨 (독립 호출) | **local** rollback-only | 템플릿이 롤백하고 값 반환. 예외 없음 |
| 기존 트랜잭션에 **참여** | **global** rollback-only | 외부 커밋 시 `UnexpectedRollbackException` |

재현에 필요한 조건은 세 가지뿐이며 **동시성이 필요 없다.**

1. 호출자가 같은 `DataSource` 위에 트랜잭션을 먼저 연다.
2. 요청 좌석 중 하나 이상이 이미 남의 것이다 (`affected_rows` 불일치).
3. 호출자가 커밋을 시도한다.

### 실패 재현 테스트

리팩터링 전에 다음 테스트를 작성해 실행했다.

```java
singleSeatRepository.hold(seat(1), OTHER_HOLD, OTHER_USER);   // 가운데 좌석을 남이 선점

assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
    observed.add(repository.holdAll(allThree(), HOLD, USER));  // 저장소는 값을 돌려준다
}))
        .isInstanceOf(UnexpectedRollbackException.class)
        .hasMessageContaining("rollback-only");

assertThat(observed).containsExactly(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);
```

**결과: 통과.** 즉 결함이 그대로 재현됐다.

```
holdAll(...)         → SEAT_UNAVAILABLE 반환        (정상 결과처럼 보인다)
외부 트랜잭션 commit → UnexpectedRollbackException  (메시지에 "rollback-only" 포함)
좌석 상태            → 부분 갱신은 롤백, 남의 홀드는 유지
```

**무엇이 나쁜가.** 저장소는 "경합으로 실패했다" 는 **예상 가능한 결과**를 돌려줬는데,
호출자는 그것을 정상 흐름으로 처리한 뒤 커밋 시점에 **기술 예외**를 맞는다.
실패의 성격이 경계를 넘으면서 바뀐다. 앱 계층은 이것을 409 로 매핑할 근거를 잃고,
`UnexpectedRollbackException` 은 5xx 로 새어나가기 쉽다 (규칙 21 위반).

---

## 4. 검토한 대안

| 대안 | 판단 | 이유 |
|---|---|---|
| **호출자 소유 트랜잭션 + 저장소 로컬 savepoint + 전용 예외** | **채택** | §5, §7 |
| 저장소가 트랜잭션을 만들지 않고 **예외 전파에만** 의존 | 부족 | 호출자가 트랜잭션 안에서 예외를 잡으면 부분 선점이 커밋된다. §7.1 에서 재현 |
| `REQUIRES_NEW` 로 좌석 트랜잭션 분리 | 배제 | §6 |
| `UnexpectedRollbackException` 을 catch 해서 숨김 | 배제 | 증상만 가린다. 외부 트랜잭션은 이미 rollback-only 라서, 그 뒤 같은 트랜잭션에서 quota 를 증가시켜도 커밋되지 않는다. 조용히 사라지는 쓰기가 생긴다 |
| 부분 UPDATE 후 보상 UPDATE 로 원복 | 배제 | 보상 자체가 실패할 수 있고, 그 사이 다른 트랜잭션이 그 좌석을 볼 수 있다. 트랜잭션이 공짜로 해주는 일을 손으로 다시 만드는 것이다 |
| `SELECT COUNT` 후 UPDATE | 배제 | TOCTOU (규칙 1). 확인과 갱신 사이가 열린다 |
| 좌석별 UPDATE 반복 | 배제 | Task 2C 가 재현한 부분 선점 결함으로 되돌아간다 (규칙 11) |

---

## 5. 선택한 트랜잭션 계약

> **유스케이스 트랜잭션 경계는 애플리케이션 서비스가 소유한다.
> 저장소는 참여만 하고, 롤백 여부를 결정하지 않는다.**

구현은 두 부분이다.

### (1) 벌크 UPDATE 를 savepoint 안에서 실행하고, 경합을 전용 예외로 알린다

```java
// 생성자: 트랜잭션 관리자를 주입받는다. 저장소가 직접 만들지 않는다.
this.savepointTemplate = new TransactionTemplate(transactionManager);
this.savepointTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

// holdAll(...)
requireEnlistedTransaction();          // ← NESTED 실행보다 먼저 (§7.4)

savepointTemplate.executeWithoutResult(status -> {
    int affectedRows = jdbc.update(HOLD_ALL_SQL, params);
    if (affectedRows != requested.value()) {
        throw new SeatUnavailableException(
                "요청한 좌석 중 하나 이상을 선점할 수 없어 요청 전체가 실패했다");
    }
});
```

savepoint 콜백 안에는 **벌크 UPDATE 와 `affected_rows` 판정만** 둔다.
예외가 콜백 밖으로 나가면 `TransactionTemplate` 이 savepoint 까지 롤백한 뒤
**같은 예외를 그대로 다시 던진다.** 저장소는 그 예외를 잡거나 다른 예외로 감싸지 않는다.

트랜잭션 관리자는 **주입**받는다. 저장소가 직접 만들면 호출자의 트랜잭션과 다른 관리자를
쓰게 되어 savepoint 가 같은 트랜잭션 안에 놓이지 않는다.

`SeatUnavailableException` 은 `reservation-domain` 에 둔다. 순수 Java 예외이므로
도메인에 Spring/JDBC/HTTP 의존성이 생기지 않는다. 앞으로 애플리케이션 서비스가
인프라를 몰라도 이 예외를 다룰 수 있다.

저장소는 남의 트랜잭션 상태를 건드리지 않는다 — `setRollbackOnly()` 를 쓰지 않으므로
외부 트랜잭션이 rollback-only 가 되지 않는다. 이것이 이전 구현과의 결정적 차이다.

원인은 구분하지 않는다. "존재하지 않는 좌석 / 이미 남이 선점 / 그 사이 상태 변경" 은
요청자에게 모두 같은 결과이며, 구분해 노출하면 **특정 좌석의 점유 여부를 탐색하는 수단**이 된다.

HTTP 409 매핑은 이 예외의 책임이 아니다. 앱/API 계층의 후속 범위이며,
**트랜잭션 경계 밖**에서 해야 한다 (§13 참고).

### (2) 배선 오류를 생성자에서 차단한다

savepoint 는 **저장소의 `DataSource` 와 트랜잭션 관리자가 같은 리소스를 가리킬 때만** 보호가 된다.
어긋나면 다음이 조용히 일어난다.

1. 호출자가 좌석 DataSource **A** 위에 트랜잭션을 연다.
2. `requireEnlistedTransaction()` 은 A 기준이라 **통과한다.**
3. savepoint 템플릿은 관리자 **B** 로 실행된다. B 에는 바인딩된 트랜잭션이 없으므로
   savepoint 가 아니라 **B 위의 새 트랜잭션**이 열린다.
4. 좌석 UPDATE 는 A 의 커넥션에서 실행되어 **savepoint 의 보호를 받지 못한다.**
5. 경합 예외를 트랜잭션 안에서 잡고 커밋하면 **부분 선점이 확정된다 — I-9 붕괴.**

문서 경고로는 막히지 않는다. **생성자에서 실패시킨다.**

```java
public JdbcMultiSeatHoldRepository(
        DataSource dataSource,
        DataSourceTransactionManager transactionManager,   // 일반 PlatformTransactionManager 가 아니다
        Duration holdDuration) {
    ...
    requireSameTransactionResource(dataSource, transactionManager);   // SQL·트랜잭션 이전
    ...
}
```

파라미터 타입을 `DataSourceTransactionManager` 로 좁힌 것은 **`getDataSource()` 로 실제 리소스를
꺼내 비교할 수 있어야 하기 때문**이다. 일반 `PlatformTransactionManager` 로는 타입 검사밖에 할 수 없다.

#### 조건은 인스턴스 동일성이 아니라 리소스 동일성이다

**"같은 관리자 인스턴스를 써야 한다" 는 설명은 틀렸다.** 서로 다른
`DataSourceTransactionManager` 인스턴스라도 같은 `DataSource` 를 관리하면 같은 스레드 리소스에
참여한다. 트랜잭션 상태는 관리자가 아니라 `TransactionSynchronizationManager` 의 스레드 로컬에 있다.

§9 의 배선 테스트 §2 가 실제 MySQL 로 이것을 확인한다 — 외부 경계는 인스턴스 A, 저장소는 인스턴스 B
로 두고도 savepoint 보호가 유지된다.

#### DataSource 프록시 정규화

비교 키는 두 단계로 정규화한다. **둘 다 Spring 자신의 동작을 그대로 따른 것이다.**

1. `TransactionAwareDataSourceProxy` 를 대상 `DataSource` 로 벗긴다.
2. `TransactionSynchronizationUtils.unwrapResourceIfNecessary(...)` 를 적용한다.

1번이 필요한 이유는 **측정으로 알게 됐다.** 처음에는 2번만 적용하고 "양쪽에 같은 프록시를 주면
키가 같으니 통과한다" 고 예상했는데 **실제로는 거부됐다.** `DataSourceTransactionManager.setDataSource` 가
`TransactionAwareDataSourceProxy` 를 받으면 **대상 DataSource 로 벗겨서** 보관하기 때문이다.
즉 관리자만 벗겨지고 저장소는 프록시를 그대로 키로 삼아 어긋났다.

같은 정규화를 `requireEnlistedTransaction()` 의 `hasResource(...)` 에도 쓴다.
생성자 검증과 런타임 검사가 다른 기준을 쓰면 한쪽만 통과하는 구멍이 생긴다.

**한계.** 2번은 `InfrastructureProxy`/`ScopedObject` 만 벗긴다. Spring 6.2 의
`LazyConnectionDataSourceProxy` 는 그 인터페이스를 구현하지 않으므로 벗겨지지 않는다.
그런 프록시를 한쪽에만 씌우면 거부된다. **거부되는 편이 안전하다** — 통과시키면 savepoint 가
다른 리소스에 생겨 I-9 가 조용히 깨진다.

### (3) 트랜잭션 없는 호출을 UPDATE 전에 거부한다

```java
// transactionResourceKey 는 생성자에서 정규화해 보관한 키다.
boolean enlisted = TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.hasResource(transactionResourceKey);
if (!enlisted) {
    throw new IllegalStateException("다좌석 선점은 호출자가 연 트랜잭션 안에서 실행해야 한다. ...");
}
```

**`isActualTransactionActive()` 만으로는 부족하다.** 다른 `DataSource` 위의 트랜잭션이
열려 있어도 그 값은 참이 된다. 그 상태로 UPDATE 하면 이 저장소의 커넥션은 그 트랜잭션에
참여하지 않고 **autocommit 으로 확정**되어, 부분 갱신을 되돌릴 수단이 사라진다.

그래서 `hasResource(...)` 로 **이 DataSource 의 커넥션이 실제로 트랜잭션 리소스로
바인딩됐는지**를 함께 본다. `JdbcTemplate` 이 `DataSourceUtils` 로 커넥션을 얻는 기준과 같다.

키는 **생성자 검증이 쓴 것과 같은 정규화된 `transactionResourceKey`** 다 (위 (2) 참고).
생성자와 런타임이 다른 기준을 쓰면 한쪽만 통과하는 구멍이 생긴다.

이 예외는 **좌석 경합이 아니라 호출자의 배선 실수**다. 그래서 `SeatUnavailableException` 이
아니라 `IllegalStateException` 이며, 두 가지를 테스트가 명시적으로 구분한다.

### 반환 타입 — `MultiSeatHoldOutcome` 제거

경합이 예외가 되면 `SEAT_UNAVAILABLE` 은 **어떤 경로로도 반환되지 않는 죽은 값**이 된다.
남는 값은 `HELD` 하나뿐인데, 값이 하나뿐인 결과 타입은 아무 정보도 전달하지 않는다.

검토한 세 가지 중 **"성공은 정상 종료로, 경합 실패는 전용 예외로"** 를 골랐다.

| 안 | 판단 |
|---|---|
| 성공은 반환값(`HELD`)으로, 실패는 예외로 | 단일 값 enum 이 남는다. 호출자가 검사할 것이 없는 반환값은 잡음이다 |
| **성공은 정상 종료(`void`), 실패는 예외로** | **채택.** "정상 종료 = 요청 좌석 전부 선점" 이 I-9 와 정확히 같은 문장이다 |
| 앱 경계 밖에서만 예외→결과 타입 변환 | 지금은 그 경계(앱 서비스)가 존재하지 않는다. 소비자가 없는데 변환 계층을 먼저 만들 근거가 없다 |

**인프라 결과 타입이 HTTP 응답 의미를 소유하지 않게** 하는 것도 이유다. 기존
`MultiSeatHoldOutcome.SEAT_UNAVAILABLE` 의 JavaDoc 은 "앱 계층이 409 로 매핑한다" 를
인프라 enum 에 적어두고 있었다. 그 서술은 도메인 예외로 옮겼다.

> 다른 저장소의 `SeatHoldOutcome` / `SeatPaymentOutcome` / `SeatConfirmationOutcome` 은
> **건드리지 않았다.** 그것들은 값이 둘 이상이고 죽은 값이 없다. 이번 변경의 근거는
> "경합이 예외가 되어 값이 하나만 남았다" 는 것이므로 그 저장소들에는 적용되지 않는다.

---

## 6. `REQUIRES_NEW` 를 쓰지 않은 이유

좌석 UPDATE 를 별도 트랜잭션으로 떼면 `UnexpectedRollbackException` 은 사라진다.
**그러나 I-12 가 불가능해진다.**

```
외부 TX (앱 서비스)          : quota 증가
  └ 내부 TX (REQUIRES_NEW)   : 좌석 선점 → 커밋 (독립적으로 확정)
외부 TX 롤백                 : quota 만 되돌아감. 좌석은 HELD 로 남는다
```

좌석은 이미 커밋됐으므로 외부 롤백이 닿지 않는다. **quota 와 좌석이 서로 다른 시점에
확정되는 순간 둘의 원자성이 깨진다.** 반대 방향도 마찬가지로, 좌석은 잡혔는데 quota 증가가
롤백되면 사용자는 상한 없이 좌석을 계속 잡을 수 있다.

또 하나: `REQUIRES_NEW` 는 **커넥션을 하나 더 점유**한다. 외부 트랜잭션이 커넥션을 쥔 채
내부 트랜잭션이 풀에서 또 하나를 가져간다. 경합 시 커넥션 풀 고갈을 앞당긴다.

---

## 7. `NESTED` / savepoint — 채택한 이유

> **정정.** 이 문서의 초안은 "savepoint 를 도입하면 원자성을 잃는다" 고 단정했다.
> **그 단정은 틀렸다.** savepoint 는 최상위 트랜잭션을 대체하는 것이 아니라 그 **안에**
> 놓이므로, 예외가 최상위까지 전파되면 좌석과 quota 역할 변경은 여전히 **함께** 롤백된다.
> 아래 §7.2 가 두 경우를 구분한다.

### 7.1 예외 전파만으로는 부족했다 — P1 재현

§5 의 초기 계약은 "경합을 예외로 알리고 롤백은 호출자가 한다" 였다.
**호출자가 트랜잭션 경계 안에서 그 예외를 잡으면 깨진다.**

```java
inTransaction(() -> {
    try {
        repository.holdAll(allThree(), HOLD, USER);   // 3석 중 1석은 남의 것
    } catch (SeatUnavailableException expected) {
        // 경합을 정상 흐름으로 처리하고 계속 진행
    }
});                                                    // ← commit
```

**RED 결과: 요청자 점유 좌석 = 2** (기대 0). 벌크 UPDATE 가 바꾼 2석이 그대로 커밋됐다.

이것을 배선 실수로 취급할 수 없다. 좌석 경합은 예상 가능한 정상 실패이고(규칙 21),
그것을 트랜잭션 안에서 처리하는 코드는 자연스럽다. **저장소가 자신의 UPDATE 만큼은
스스로 되돌려야 한다.** savepoint 가 정확히 그 범위를 준다.

### 7.2 두 경우가 다르게 동작한다

| 호출자의 처리 | savepoint | 최상위 트랜잭션 | 결과 |
|---|---|---|---|
| **예외를 경계 밖으로 전파** | 롤백 | **롤백** | 좌석 변경과 quota 역할 부수 변경이 **함께 사라진다** |
| **트랜잭션 안에서 catch** | 롤백 | **계속 진행 가능** | 저장소의 부분 좌석 변경만 이미 되돌아간 상태. 호출자는 커밋할 수 있다 |

두 경우 모두 **요청자의 부분 선점은 0석**이다. 그것이 I-9 다.

후자에서 무엇을 커밋할지는 호출자의 책임이다 — §13 의 남은 위험 참고.

### 7.3 왜 원자성을 잃지 않는가

savepoint 는 **같은 트랜잭션·같은 커넥션** 안에 있다. 최상위 트랜잭션을 나누지 않는다.
그래서 성공한 선점은 여전히 최상위 커밋 시점에만 확정되고, 최상위가 롤백하면 함께 사라진다.

이것이 `REQUIRES_NEW` 와의 결정적 차이다. `REQUIRES_NEW` 는 좌석을 **독립적으로 커밋**하므로
최상위 롤백이 닿지 않는다. §9 의 테스트 4(`성공 후 외부 rollback`)가 그 차이를 고정한다 —
현재 구현에서 외부 롤백 시 좌석 변경이 사라지므로, 벌크 UPDATE 가 독립 트랜잭션이 아니라
**최상위 트랜잭션 안에 있음**이 확인된다.

### 7.4 MySQL·Spring 조합에서의 지원 여부

`DataSourceTransactionManager` 는 savepoint 를 지원하고 InnoDB 도 `SAVEPOINT` 를 지원한다.
Testcontainers MySQL 8.4 에서 실제로 동작함을 §9 의 테스트로 확인했다.
`NestedTransactionNotSupportedException` 은 발생하지 않았다.

**단, 순서가 중요하다.** Spring 의 `PROPAGATION_NESTED` 는 **기존 트랜잭션이 없으면
savepoint 대신 새 트랜잭션을 연다**(`REQUIRED` 처럼 동작). 그대로 두면 저장소가 최상위
트랜잭션을 만들게 되어 계약이 깨진다. 그래서 `requireEnlistedTransaction()` 을
**NESTED 실행보다 먼저** 호출한다.

---

## 8. 트랜잭션 없는 직접 호출을 차단한 이유

차단하지 않으면 다음이 조용히 일어난다.

```
트랜잭션 없음 → jdbc.update(...) 가 autocommit 으로 실행
3석 중 2석만 가능 → 그 2석이 즉시 확정됨
예외를 던져도 되돌릴 트랜잭션이 없다 → 부분 선점이 영구히 남는다
```

**예외를 던지는 것만으로는 I-9 를 지킬 수 없다.** 되돌릴 경계가 있어야 예외에 의미가 생긴다.
그래서 UPDATE 를 보내기 **전에** 검사한다. 실패해도 DB 는 건드려지지 않는다.

이 선택의 대가는 **호출자가 반드시 트랜잭션을 열어야 한다**는 것이다.
저장소를 단독으로 쓰던 기존 테스트들은 전부 테스트용 경계를 열도록 바꿨다.
운영 코드에서는 애플리케이션 서비스가 그 역할을 맡는다.

---

## 9. 검증 결과

`MultiSeatHoldTransactionBoundaryTest` — Testcontainers 실제 MySQL 8.4 (H2·mock 미사용, 규칙 26).

| # | 검증 | 결과 |
|---|---|---|
| 1 | **트랜잭션 없는 직접 호출** | |
| | `IllegalStateException` 으로 UPDATE 전에 거부 | 통과 |
| | 어떤 좌석도 변경되지 않음 (`version` 0 유지) | 통과 |
| | 경합 조건에서도 부분 선점이 autocommit 으로 남지 않음 | 통과 |
| 2 | **외부 트랜잭션 전체 성공 후 commit** | |
| | 요청 좌석 전부 `HELD` | 통과 |
| | `hold_id`·`held_by`·`held_at`·`expires_at` 일관 기록 | 통과 |
| | `version` 정확히 1 증가 | 통과 |
| 3 | **외부 트랜잭션에서 일부 좌석 경합** | |
| | `SeatUnavailableException` 전달 | 통과 |
| | `UnexpectedRollbackException` 으로 바뀌지 않음 (원인 사슬 포함) | 통과 |
| | 요청자가 부분적으로 잡은 좌석 0개 | 통과 |
| | 다른 사용자의 기존 홀드 유지 | 통과 |
| 4 | **성공 후 외부 rollback** | |
| | 저장소 작업 자체는 성공 (트랜잭션 내부에서 3석 관측) | 통과 |
| | 외부 롤백 시 좌석 변경 미반영 (`version` 0) | 통과 |
| 5 | **같은 트랜잭션의 부수 DB 변경과 원자성** | |
| | 성공 시 좌석과 부수 변경이 함께 commit | 통과 |
| | 경합 예외를 **전파**하면 좌석 부분 변경과 사전 기록한 부수 변경이 함께 rollback | 통과 |
| 6 | **★ 호출자가 트랜잭션 안에서 예외를 catch** | |
| | 예외를 잡고 commit 해도 요청자 부분 선점 0개 | 통과 *(RED→GREEN)* |
| | 다른 사용자의 기존 홀드 유지 | 통과 |
| | 외부 트랜잭션이 `UnexpectedRollbackException` 없이 정상 commit | 통과 |
| | savepoint **이전**에 기록한 변경은 롤백되지 않음 | 통과 |
| | 예외를 잡은 뒤 기록한 부수 변경은 commit 됨 | 통과 *(RED→GREEN)* |

`MultiSeatHoldWiringTest` — 생성자 배선 계약.

| # | 검증 | 결과 |
|---|---|---|
| 1 | 같은 DataSource · **같은** 관리자 인스턴스 → 생성 성공, 전체 선점 성공, savepoint 롤백 동작 | 통과 |
| 2 | 같은 DataSource · **다른** 관리자 인스턴스 → 생성 허용, 외부 트랜잭션 참여, 예외를 안에서 잡고 commit 해도 부분 선점 0개, `UnexpectedRollbackException` 없음 | 통과 |
| 3 | **다른** DataSource 관리자 → `IllegalArgumentException`, 메시지에 리소스 불일치 표시, 좌석 SQL 미실행(상태·`version` 불변) | 통과 |
| 4 | 프록시 정규화 — 양쪽 프록시 / 저장소만 프록시 → 허용, 프록시여도 savepoint 보호 유지 | 통과 |

§3 의 "다른 DataSource" 는 **커넥션을 요구하면 예외를 던지는 테스트 더블**로 구성했다.
생성자 검증이 DB 를 건드리지 않는다는 것이 그 더블 자체로 증명된다 — SQL 이 실행됐다면
`UnsupportedOperationException` 이 났을 것이다. 이 테스트의 대상은 배선 검증이지 SQL·잠금 동작이
아니므로 실제 MySQL 이 필요하지 않다. InnoDB 동작이 필요한 검증은
`MultiSeatHoldTransactionBoundaryTest` 가 Testcontainers 로 수행한다 (규칙 26 은 후자에 적용된다).

### 부수 변경 테스트를 구성한 방법

`user_hold_quota` 가 놓일 자리를 흉내내되 **운영 마이그레이션을 추가하지 않았다.**
테스트 안에서 `task2f_side_effect` 테이블을 `CREATE TABLE IF NOT EXISTS` 로 만든다.
MySQL 에서 DDL 은 암묵적 커밋을 일으키므로 반드시 트랜잭션 **밖**에서 실행한다.

이 테스트가 확인하는 것은 **savepoint 의 로컬 롤백 범위와 호출자의 책임 구분**이다.
결과는 호출자가 예외를 어떻게 다루느냐에 따라 갈리며, 둘 다 정상이다.

| 호출자의 처리 | 좌석 부분 변경 | 같은 트랜잭션의 부수 변경 |
|---|---|---|
| 예외를 **경계 밖까지 전파** | savepoint 가 먼저 롤백 → 최상위도 롤백 | **함께 사라진다** |
| 예외를 **트랜잭션 안에서 catch** | savepoint 로 롤백 (요청자 점유 0석) | **커밋될 수 있다** — savepoint 이전·이후 모두 |

두 번째 줄이 **savepoint 를 배제할 근거가 아니다.** savepoint 는 이 저장소의 UPDATE 만
되돌리도록 설계된 것이고, 유스케이스 전체의 원자성은 여전히 최상위 트랜잭션이 책임진다.
좌석 없이 부수 변경만 커밋할지는 **호출자가 결정하는 일**이며, 그 결정을 잘못하면 안 된다는
것이 §13 의 남은 위험이다.

### 명확히 구분해야 할 것

> **"예외가 발생하지 않는다" 와 "`UnexpectedRollbackException` 이 발생하지 않는다" 는 다르다.**
> 경합 시 `SeatUnavailableException` 은 **정상적으로 전달되어야 한다.**
> 금지되는 것은 그것이 기술 예외로 바뀌는 것이다. 테스트는 원인 사슬까지 순회해 확인한다.

---

## 10. I-9 가 계속 유지되는 근거

계약이 바뀌어도 I-9 의 세 축은 그대로다.

| 축 | 근거 | 상태 |
|---|---|---|
| **단일 벌크 UPDATE** | `affected_rows` 하나로 전부/전무 판정. 좌석별 반복 없음 (규칙 11) | 변경 없음 |
| **부분 갱신 롤백** | 이전엔 `setRollbackOnly()`. 지금은 **저장소의 NESTED savepoint 롤백으로 부분 갱신을 먼저 제거**하고, 예외가 최상위 경계까지 전파되면 **유스케이스 전체도 롤백** | 수단 교체 + 로컬 경계 추가 |
| **잠금 순서** | `SeatHoldPolicy.lockOrder` 정렬 + `FORCE INDEX (PRIMARY)` (규칙 2·3) | 변경 없음 |

기존 I-9 테스트는 전부 유지하고 호출부만 트랜잭션 경계 안으로 옮겼다.

| 기존 계약 | 결과 |
|---|---|
| 1·2·4석 전체 성공 | 통과 |
| 하나라도 불가능하면 요청자 점유 0석 | 통과 |
| 요청 좌석 ID 오름차순 잠금 (입력 순서 무관) | 통과 |
| `EXPLAIN` → `FORCE INDEX (PRIMARY)` | 통과 |
| 호출자 컬렉션 불변 / 불변 리스트 처리 | 통과 |
| 다른 사용자의 홀드 보호 | 통과 |
| 동시 경합 200요청 × 4시나리오에서 부분 선점 0 | 통과 |

동시성 테스트에서는 **각 작업자가 각자 독립된 트랜잭션을 연다.** 경합 실패는
`SeatUnavailableException` 을 잡아 `failed` 로 세고, 그 외 예외만 `errors` 로 모아 단언한다.
경합을 오류로 집계하지 않는다 (규칙 21).

> 동시성 결과는 증명이 아니라 **통계적 보조 근거**다 (규칙 30).
> 구조적 논증은 §5 의 계약, 결정적 재현은 §9 의 경계 테스트다.

---

## 11. 이번 Task 에서 I-12 를 구현하지 않은 이유

**순서 때문이다.** quota 증감은 좌석 UPDATE 와 하나의 트랜잭션이어야 하고,
그러려면 저장소가 외부 트랜잭션에 참여해야 한다. 그런데 **참여하는 순간 깨지는 구조**였다.

quota 를 먼저 넣었다면 `UnexpectedRollbackException` 을 quota 로직과 뒤섞인 상태로 만났을 것이고,
원인이 좌석 경로에 있는지 카운터 경로에 있는지 분리하기 어려웠을 것이다.

또한 이번 변경만으로 quota 가 저절로 되는 것도 아니다. §12 에 남은 결정 사항이 있다.

---

## 12. 다음 Task — 애플리케이션 서비스와 quota 결합 시 주의점

### 포트 인터페이스의 위치

이번 Task 에서는 **포트를 추출하지 않았다.** 소비자가 아직 하나도 없고,
구현체 하나를 위한 인터페이스는 이득 없이 간접 계층만 늘린다.

다음 앱 배선 단계에서 만들 때의 위치는 이렇게 기록해 둔다.

```
reservation-domain          포트 인터페이스 (예: SeatHoldPort)   ← 도메인이 소유
reservation-infra           Jdbc… 구현체가 그 포트를 구현
apps/reservation-service    애플리케이션 서비스가 포트에 의존 + 트랜잭션 경계 소유
```

포트가 도메인에 있어야 의존 방향이 `infra → domain` 으로 유지된다 (CLAUDE.md 모듈 규칙 1).
포트를 infra 에 두면 앱이 infra 에 직접 묶인다.

### quota 결합 시 결정할 것

0. **DataSource 와 트랜잭션 관리자를 하나씩만 둔다.** 좌석 저장소와 애플리케이션 서비스의
   트랜잭션 경계가 **같은 `DataSource` 리소스**를 써야 한다. Spring Boot 기본 구성처럼
   `DataSource` 빈 하나 + `DataSourceTransactionManager` 빈 하나면 자동으로 만족한다.
   여러 DataSource 를 도입하는 순간 이 계약이 위험해지며, 생성자가 그 배선을 거부한다.
   프록시를 쓸 일이 있다면 **저장소와 관리자에 같은 참조**를 준다.

1. **트랜잭션 경계는 애플리케이션 서비스가 연다.** 저장소는 최상위 트랜잭션을 만들지 않으므로,
   경계를 열지 않으면 `IllegalStateException` 으로 즉시 드러난다. 조용히 autocommit 되지 않는다.

2. **quota 증가와 좌석 선점의 순서.** 좌석 선점이 예외를 던지면 quota 증가도 함께 롤백된다.
   반대로 quota 검사(조건부 UPDATE, 규칙 8)가 먼저 실패하면 좌석 UPDATE 는 실행되지 않는다.
   어느 쪽을 먼저 둘지는 **잠금 보유 시간**으로 판단해야 하며, 아직 측정하지 않았다.

3. **감소량은 실제 `affected_rows` 기준.** 확정(2B)·자발적 해제(2E)는 한 사용자의 한 홀드를
   대상으로 하므로 실제 변경 행 수를 그대로 쓸 수 있다. **만료 스위퍼(2D)는 다르다** —
   여러 사용자를 한 벌크 UPDATE 로 회수하므로 전체 `affected_rows` 만으로는 누구의 좌석이
   몇 석 회수됐는지 알 수 없다. 현재 `JdbcSeatExpiryRepository.expire(...)` 는 `int` 만 반환하고
   `ExpiryCandidate` 는 `(SeatId, HoldId)` 로 `held_by` 를 담지 않는다.
   **사용자별 집계가 가능한 반환 구조 또는 별도 설계가 필요하다.**

4. **다좌석 선점의 quota 증가량은 요청 좌석 수다.** 전부-또는-전무이므로 부분 성공이 없고,
   실패하면 예외로 전체가 롤백된다. 해제·만료와 달리 여기서는 `affected_rows` 와 요청 수가
   같을 때만 성공이므로 두 값이 일치한다.

5. **다른 저장소의 트랜잭션 계약도 점검해야 한다.** 이번에 고친 것은
   `JdbcMultiSeatHoldRepository` 하나다. 해제·만료·확정 저장소는 자체 트랜잭션을 만들지
   않지만, **트랜잭션 없이 호출하면 autocommit 으로 동작한다.** 그 경로들이 quota 와 묶이는
   시점에 같은 enlistment 가드가 필요한지 판단해야 한다. 이번 Task 는 범위를 넘지 않았다.

---

## 13. 남은 위험

| 항목 | 상태 |
|---|---|
| **enlistment 가드가 `JdbcMultiSeatHoldRepository` 에만 있다** | 해제·만료·확정 저장소는 트랜잭션 없이 호출하면 여전히 autocommit 이다. 각각은 단일 문장이라 부분 갱신이 없지만, quota 와 묶이는 시점에는 같은 판단이 필요하다 |
| **애플리케이션 서비스가 없다** | 트랜잭션 경계를 실제로 소유할 주체가 아직 없다. 지금은 테스트가 그 역할을 대신한다 |
| **`SeatUnavailableException` 의 HTTP 매핑 미구현** | 409 매핑은 앱/API 계층의 후속 책임이다. 매핑이 없으면 5xx 로 새어나갈 수 있다 |
| **`innodb_lock_wait_timeout` 은 여전히 테스트 픽스처에만 적용** | 운영 DataSource 가 없다. 규칙 9 는 DataSource 구성의 책임이며 저장소가 강제할 수 없다 |
| **잠금 보유 시간 미측정** | 트랜잭션 경계가 저장소 밖으로 나가면서 **잠금 보유 구간이 호출자의 트랜잭션 길이에 종속**된다. 앱 서비스가 트랜잭션 안에서 다른 작업을 오래 하면 좌석 행 잠금이 그만큼 길어진다. 규칙 10(트랜잭션 안 외부 I/O 금지)이 더 중요해졌다. 아직 측정하지 않았다 |
| **I-12 미구현** | `user_hold_quota` 테이블 자체가 없다 |
| **규칙 32 감사 로그 / 규칙 35 메트릭** | 여전히 없다. 롤백된 요청은 흔적을 남기지 않으므로 경합 분석 데이터가 없다 |

---

## 14. 재현 명령

```bash
./gradlew :modules:reservation-infra:test --tests "*MultiSeatHoldTransactionBoundaryTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcMultiSeatHoldRepositoryTest"
./gradlew :modules:reservation-infra:test --tests "*MultiSeatHoldConcurrencyTest"
./gradlew :modules:reservation-infra:test --tests "*MultiSeatHoldPartialFailureTest"
```

Docker 가 실행 중이어야 한다.
