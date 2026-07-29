# RailGate — 개발 가이드

> 상태: 초안 v0.1 (2026-07-28)
> 함께 읽을 문서: [INVARIANTS.md](INVARIANTS.md), [ARCHITECTURE.md](ARCHITECTURE.md), [REQUIREMENTS.md](REQUIREMENTS.md)

이 문서는 RailGate를 구현할 때 지킬 OOP, DDD, TDD, Clean Code 기준이다.
원칙의 목적은 멋진 용어를 지키는 것이 아니라, 명절 예매처럼 경합이 심한 상황에서도 불변식을 깨뜨리지 않는 코드를 만드는 것이다.

---

## 1. 개발 순서

1. 요구사항을 `docs/REQUIREMENTS.md` 의 FR/NFR 번호로 먼저 확인한다.
2. 영향을 받는 불변식을 `docs/INVARIANTS.md` 에서 찾는다.
3. 불변식이 없으면 기능보다 먼저 불변식을 추가하거나 기존 불변식과의 관계를 문서화한다.
4. 실패하는 테스트를 먼저 작성한다.
5. 가장 작은 구현으로 통과시킨다.
6. 이름, 중복, 책임 경계를 정리한다.
7. `./gradlew check` 로 테스트와 아키텍처 규칙을 통과시킨다.

---

## 2. OOP 규칙

### 값 객체

- ID, 수량, 순번, 좌석 번호, 금액, 만료 시각처럼 의미가 있는 원시값은 값 객체로 감싼다.
- 값 객체는 불변으로 만든다.
- 생성 시점에 유효성을 검증한다. 잘못된 값 객체가 존재하게 두지 않는다.
- 값 객체끼리 비교할 때 원시값을 꺼내 비교하는 코드를 반복하지 않는다.

예:

```java
public record SeatCount(int value) {
    public SeatCount {
        if (value < 1 || value > 4) {
            throw new IllegalArgumentException("seat count must be between 1 and 4");
        }
    }
}
```

### 엔티티

- 엔티티의 상태 변경은 엔티티 메서드로 표현한다.
- `setStatus(...)` 처럼 임의 전이를 허용하는 setter를 만들지 않는다.
- 상태 전이는 메서드 이름에 도메인 행위를 드러낸다.
  예: `hold(...)`, `startPayment(...)`, `confirm(...)`, `expire(...)`, `release(...)`
- 엔티티는 자신의 불변식을 스스로 지킨다. 서비스가 엔티티 내부 상태를 조립해 맞추게 하지 않는다.

### 도메인 서비스

- 도메인 서비스는 여러 엔티티/값 객체가 함께 필요할 때만 만든다.
- 단순히 엔티티 메서드를 대신 호출하는 빈 서비스는 만들지 않는다.
- 외부 I/O, DB, Redis, Kafka, HTTP 호출은 도메인 서비스에 넣지 않는다.

---

## 3. DDD 규칙

### 바운디드 컨텍스트

RailGate의 핵심 컨텍스트는 둘이다.

| 컨텍스트 | 책임 | 대표 모듈 |
|---|---|---|
| Queue | 대기열 등록, 순번, 승격, 입장 토큰 발급 | `modules/queue-domain`, `apps/queue-service` |
| Reservation | 좌석 선점, 결제, 확정, 만료 | `modules/reservation-domain`, `apps/reservation-service` |

- Queue와 Reservation은 서로의 도메인 타입을 직접 참조하지 않는다.
- 두 컨텍스트의 유일한 공유 접점은 `modules/queue-token` 이다.
- 공통 모듈에는 정말 공통인 타입만 둔다. 편해서 넣는 순간 경계가 흐려진다.

### 애그리거트

- 애그리거트는 하나의 일관성 경계를 뜻한다.
- 다른 애그리거트 내부 컬렉션을 직접 수정하지 않는다.
- 트랜잭션 하나에서 지켜야 하는 규칙만 같은 애그리거트에 둔다.
- 조회 편의를 위해 애그리거트를 키우지 않는다.

초기 후보:

| 애그리거트 | 지키는 규칙 |
|---|---|
| QueueEntry | 동일 사용자 중복 등록 금지, 순번 불변 |
| AdmissionSession | 입장 권한 TTL, 토큰 바인딩 |
| SeatInventory | 좌석 상태 전이, 좌석당 확정 1건 |
| Hold | 여러 좌석 전부 성공/실패, 만료, 해제 |
| Payment | 승인, 매입, UNKNOWN 재조회 |

### 애플리케이션 서비스

- 애플리케이션 서비스는 유스케이스 조립자다.
- 트랜잭션 경계, 저장소 호출, 도메인 객체 호출, 이벤트 기록을 조정한다.
- 비즈니스 규칙 자체는 가능한 한 도메인 모듈에 둔다.
- 트랜잭션 안에서는 외부 I/O를 호출하지 않는다.

권장 흐름:

```text
Controller -> ApplicationService -> Domain -> Repository/Port -> Infra
```

---

## 4. TDD 규칙

### 기본 루프

1. Red: 깨져야 하는 테스트를 먼저 쓴다.
2. Green: 가장 단순한 구현으로 통과시킨다.
3. Refactor: 이름, 중복, 책임을 정리한다.

### 테스트 우선순위

| 대상 | 테스트 방식 | 속도 기준 |
|---|---|---|
| 값 객체 / 상태 머신 | 순수 단위 테스트 | 매우 빠름 |
| 애플리케이션 서비스 | 포트 fake 또는 mock | 빠름 |
| DB 조건부 UPDATE / 잠금 | Testcontainers 통합 테스트 | 느려도 정확해야 함 |
| 동시성 | `CountDownLatch` 동시 출발 | flaky 금지 |
| 부하/정합성 | k6 + consistency SQL | 별도 단계 |

### 테스트 이름

- 테스트 이름은 구현이 아니라 규칙을 말한다.
- 한국어를 써도 된다. 중요한 것은 실패했을 때 무엇이 깨졌는지 바로 보이는 것이다.

예:

```java
@Test
void 같은_좌석을_1000명이_동시에_선점하면_정확히_1명만_성공한다() {
}
```

### 동시성 테스트

- `Thread.sleep` 으로 타이밍을 맞추지 않는다.
- 모든 작업자는 `CountDownLatch` 로 동시에 출발한다.
- 성공 개수, 실패 개수, 최종 DB 상태를 함께 검증한다.
- 새 동시성 방어를 추가할 때는 순진한 구현으로 먼저 실패를 재현한 뒤 방어를 넣는다.

---

## 5. Clean Code 규칙

### 이름

- 도메인 언어를 이름에 쓴다. `process`, `handle`, `data`, `info` 같은 넓은 이름은 피한다.
- Boolean 메서드는 질문처럼 읽히게 한다.
  예: `isExpiredAt(...)`, `canBeConfirmedBy(...)`
- 경합 실패와 시스템 오류를 이름에서 구분한다.
  예: `SeatAlreadyHeldException` 은 정상 409, `SeatStateTransitionException` 은 규칙 위반이다.

### 메서드

- 한 메서드는 한 추상화 수준만 다룬다.
- 조건이 복잡하면 정책 객체나 값 객체 메서드로 옮긴다.
- 반복되는 조건문보다 상태 전이 메서드와 전이표 테스트를 우선한다.

### 예외

- 도메인 규칙 위반은 도메인 예외로 표현한다.
- HTTP 상태 코드는 앱 계층에서 매핑한다.
- `409`, `429`, `202` 는 정상 흐름으로 다룬다. ERROR 로그 대상이 아니다.

### 주석

- 코드가 말해야 하는 내용을 주석으로 대신하지 않는다.
- 동시성, 잠금 순서, 멱등성 트랜잭션 경계처럼 코드만으로 의도를 알기 어려운 곳에는 짧은 주석을 남긴다.

---

## 6. 패키지 규칙

도메인 모듈은 기술 레이어가 아니라 도메인 개념 중심으로 나눈다.

```text
modules/reservation-domain
└── com.railgate.reservation
    ├── seat
    ├── hold
    ├── payment
    └── reservation
```

앱 모듈은 입출력과 유스케이스 조립을 분리한다.

```text
apps/reservation-service
└── com.railgate.reservation
    ├── api
    ├── application
    ├── config
    └── infra
```

규칙:

- `api` 는 HTTP 요청/응답 DTO와 컨트롤러만 둔다.
- `application` 은 유스케이스와 트랜잭션 경계를 둔다.
- `infra` 는 DB, Redis, 외부 HTTP, 메시징 구현을 둔다.
- 도메인 모듈은 `api`, `application`, `infra` 를 알지 못한다.

---

## 7. Definition of Done

새 기능은 다음을 만족해야 완료다.

- 관련 FR/NFR 또는 불변식 ID가 PR/커밋/작업 기록에 남아 있다.
- 도메인 규칙은 순수 단위 테스트가 있다.
- DB 정합성 규칙은 Testcontainers 통합 테스트가 있다.
- 동시성 규칙은 `CountDownLatch` 테스트가 있다.
- 상태 변경 POST는 `Idempotency-Key` 정책을 따른다.
- 오류 응답은 `{ code, message, traceId }` 형식을 따른다.
- 로그에 민감 정보가 남지 않는다.
- `./gradlew check` 가 통과한다.

---

## 8. Claude Code 작업 프롬프트 예시

새 기능을 Claude Code로 진행할 때는 다음 형태로 요청한다.

```text
RailGate 프로젝트에서 FR-2.3 좌석 선점 기능을 구현해줘.
관련 불변식은 I-8, I-9, I-12, I-18 이야.

규칙:
- docs/INVARIANTS.md, docs/ARCHITECTURE.md, docs/DEVELOPMENT_GUIDE.md 를 먼저 읽어줘.
- 도메인 로직은 modules/reservation-domain 에 순수 Java로 작성해줘.
- Spring/JPA 코드는 apps/reservation-service 또는 추후 infra 모듈에만 둬.
- 테스트를 먼저 작성하고, 동시성 테스트는 CountDownLatch 를 사용해줘.
- 마지막에 ./gradlew check 를 실행해줘.
```

작업이 커지면 한 번에 "예매 전체"를 만들지 말고, 불변식 단위로 작게 자른다.
