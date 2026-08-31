# RailGate — 개발 규칙

## 프로젝트 목적

명절 기차 예매처럼 특정 시점에 수백만 명이 몰리는 환경에서, 예매 시스템의 **정합성**과 **성능**을
코드가 아니라 **측정 결과**로 증명하는 학습·포트폴리오 프로젝트다.

기능 추가보다 **불변식 유지가 항상 우선**한다. 불변식 목록은 `docs/INVARIANTS.md`.
OOP/DDD/TDD/Clean Code 기준은 `docs/DEVELOPMENT_GUIDE.md` 를 따른다.

---

## 개발 환경

| 항목 | 값 |
|---|---|
| Java | **21** (Gradle toolchain 으로 고정) |
| Spring Boot | 4.1.0 |
| Gradle | 9.6.1 (wrapper 사용) |
| queue-service | http://localhost:18080 |
| reservation-service | http://localhost:18081 |

> ⚠️ **이 머신의 기본 `java` 는 17 이다.**
> Gradle toolchain 은 *빌드* 에만 적용되므로 `java -jar` 로 직접 실행하면
> `UnsupportedClassVersionError` 가 난다. 아래 명령을 쓸 것.
>
> ⚠️ **8080/8081 포트는 이 머신에서 이미 사용 중이다.** 그래서 18080/18081 을 쓴다.
> 헬스체크가 성공했다고 우리 앱이 뜬 것이라고 단정하지 말고, 로그에서 서버 종류와 포트를 확인할 것.

### 자주 쓰는 명령

```bash
./gradlew build                         # 전체 빌드 + 테스트
./gradlew check                         # 테스트 + 아키텍처 규칙 검증
./gradlew :apps:queue-service:bootRun   # 실행 (toolchain JDK 자동 사용)
./gradlew :apps:reservation-service:bootRun
./gradlew projects                      # 모듈 구조 확인
./gradlew :modules:reservation-domain:test --tests "*SeatStatusTest"

# jar 직접 실행이 필요하면 Java 21 을 명시한다
/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home/bin/java \
  -jar apps/queue-service/build/libs/queue-service-0.1.0-SNAPSHOT.jar
```

---

## 모듈 구조와 의존 규칙

```
apps/queue-service        WebFlux   ─┬─> modules/queue-domain ──> modules/common
apps/reservation-service  MVC       ─┼─> modules/reservation-domain ──> modules/common
                                     └─> modules/queue-token ──> modules/common   ★ 유일한 공통 접점
```

**절대 규칙**

1. 의존 방향은 항상 `apps → modules`. 그 역은 금지.
2. `*-domain` 모듈의 **main 소스에 Spring / JPA / Redis / 네트워크 의존성을 두지 않는다.**
   순수 Java 여야 인프라 없이 상태 머신을 빠르게 테스트할 수 있다.
3. `queue-*` 와 `reservation-*` 는 **서로를 직접 참조하지 않는다.**
   공통 접점은 `modules/queue-token` 하나뿐이다.
4. **두 서비스는 서로 동기 HTTP 호출을 하지 않는다.**
   입장 여부는 서명된 토큰 검증으로 확인한다. 좌석 API 가 대기열 서비스의 가용성에 종속되면 안 된다.
5. 새 의존성은 `gradle/libs.versions.toml` 에 먼저 등록한다. 모듈 빌드 파일에 버전 문자열을 직접 쓰지 않는다.
6. 저장소(repositories) 선언은 `settings.gradle.kts` 에만 둔다.
7. `./gradlew check` 는 도메인 순수성, 모듈 경계, 외부 의존성 버전 직접 명시 금지를 검증한다.

---

## 동시성 규칙 (위반 시 리뷰 반려)

1. **좌석 상태 변경은 반드시 조건부 UPDATE 로 하고 `affected_rows` 로 성공을 판정한다.**
   `SELECT` 로 상태를 확인한 뒤 `UPDATE` 하는 코드는 금지 (TOCTOU).
2. **여러 행을 잠그는 트랜잭션은 예외 없이 `seat_inventory.id` 오름차순으로 접근한다.**
   `IN` 절에도 정렬된 리스트를 전달한다. **만료 스위퍼도 이 규칙의 예외가 아니다**
   (인덱스 순서로 잠그면 확정 경로와 교차해 데드락이 난다 → 후보 SELECT + 정렬 후 UPDATE 2단계로).
3. `IN` 절 UPDATE 는 `FORCE INDEX (PRIMARY)` 를 붙인다.
   옵티마이저가 다른 인덱스를 고르면 잠금 순서 보장이 사라진다. `EXPLAIN` 어서션 테스트로 강제한다.
4. **좌석 확정·만료 UPDATE 에는 반드시 `hold_id` 조건을 포함한다.**
   만료 처리가 이미 확정된 예약의 좌석을 해제하면 안 된다 (I-11).
5. **Redis 분산 락을 정합성 보장 수단으로 쓰지 않는다.**
   DB 부하 감소용 최적화로만 허용하며, 그 경우에도 DB 조건부 UPDATE 를 반드시 병행한다.
6. 분산 락 해제는 **값 비교 후 삭제(CAS)** 로 한다. `DEL` 만 하면 남의 락을 푼다.
7. **만료 판정은 MySQL `NOW(3)` 을 쓴다.** 애플리케이션 시각 금지 (인스턴스 간 클럭 드리프트).
8. 집계로 한도를 검증하지 않는다 (`SELECT COUNT(*)` 후 판단 금지).
   **카운터 행에 조건부 UPDATE** 로 검사와 증가를 원자화한다 (1인당 좌석 상한).
9. 홀드 트랜잭션은 `innodb_lock_wait_timeout = 3` 을 설정한다.
   기본값 50초는 경합 시 커넥션 풀을 고갈시킨다.

---

## 트랜잭션 규칙

10. **트랜잭션 안에서 외부 I/O 금지.** PG 호출, Kafka 발행, HTTP 호출, 파일 I/O 전부.
    커넥션 풀 고갈의 1번 원인이다.
11. 트랜잭션은 최대한 짧게. 여러 좌석 처리는 반복문이 아니라 **단일 벌크 UPDATE** 로.
12. 이벤트 발행은 **Outbox 테이블 INSERT** 로 한다. 직접 `kafkaTemplate.send()` 금지.
13. **Outbox Poller 는 1대만 실행한다.** 다중화하면 같은 aggregate 의 이벤트 순서가 역전된다.
    (`SKIP LOCKED` 는 행 분배만 할 뿐 발행 순서를 보장하지 않는다)

---

## 멱등성 규칙

14. 상태를 변경하는 모든 POST 는 `Idempotency-Key` 헤더를 **필수**로 요구한다.
    누락 시 `400`. 선택이면 없는 것과 같다.
15. 멱등키는 **리스(lease) 방식**으로 관리한다. `IN_PROGRESS` 로 영구히 잠기면 안 된다.
    리스가 만료된 행은 다음 요청이 인수(takeover)할 수 있어야 한다.
16. 멱등키 처리의 트랜잭션 경계는 **3단계**로 고정한다.
    `TX0 키 확보(REQUIRES_NEW)` → `TX1 본 작업` → `TX2 결과 기록(REQUIRES_NEW)`.
    한 트랜잭션에 넣으면 중복 키 예외 후 기존 응답을 읽을 수 없어 500 이 나간다.
17. 재요청 시에는 **저장된 최초 응답을 그대로** 반환한다. 재실행하지 않는다.
18. **삭제/해제 API 는 자연 멱등이 되게 만든다.** `WHERE hold_id = ?` 조건을 붙이고,
    영향 행이 0 이어도 성공(204)으로 응답한다. 대상 기준으로 지우면 남의 홀드를 파괴한다.
19. Kafka 컨슈머는 **반드시** `processed_event` INSERT 를 같은 트랜잭션에 포함한다.
20. 외부 결제 호출에는 **우리가 만든 `merchantRef`** 를 넘긴다.
    타임아웃 시 PG 가 준 거래 ID 는 없으므로, 그것으로 재조회하는 설계는 동작하지 않는다.

---

## 응답 규약

21. `409 Conflict` = 좌석 경합 실패. **정상 동작이며 오류율/로그 ERROR 에 포함하지 않는다.**
22. `429` = rate limit / 입장 제한. 오류 아님. `Retry-After` 헤더 필수.
23. `202 Accepted` = 결제 결과 미확정(`UNKNOWN`). 클라이언트 폴링 필요.
24. 오류 응답은 `{ code, message, traceId }` 형식을 지킨다.

---

## 테스트 규칙

25. **동시성 테스트는 `CountDownLatch` 로 동시 출발시킨다.** `Thread.sleep` 타이밍 조절 금지 (flaky).
26. 통합 테스트는 **Testcontainers** 를 쓴다. 임베디드 DB/Redis 금지 (실제 잠금 동작이 다르다).
27. **한 번도 실패한 적 없는 동시성 테스트는 테스트가 아니다.**
    새 동시성 방어를 넣을 때는 순진한 구현으로 **먼저 실패시킨 기록**을 남긴다.
28. 새 상태 전이를 추가하면 **반드시 대응하는 동시성 테스트를 함께** 추가한다.
29. 부하 테스트 후 `load-test/verify/consistency.sql` 이 **한 행도 반환하지 않아야** 한다.
30. **부하 테스트 결과를 "증명" 이라고 쓰지 않는다.** 반증에 실패한 것이다.
    주장은 3층으로 분리한다: 구조적 논증 / 결정적 테스트 / 통계적 corroboration.
31. k6 프로세스 CPU 가 90% 를 넘은 측정은 **무효** 처리한다 (부하 생성기가 병목이면 병목을 오독한다).

---

## 관측성 규칙

32. 모든 좌석 상태 전이는 `seat_state_log` 에 `actor`, `reason`, `traceId` 와 함께 기록한다.
33. 로그는 JSON 구조화. `traceId`, `userId`, `reservationId`, `holdId` 를 MDC 에 넣는다.
34. 로그에 **JWT 원문, 결제 카드 정보, 전체 요청 바디를 남기지 않는다.**
35. 새 기능에는 대응하는 Micrometer 메트릭을 함께 추가한다.

---

## 설계 결정 시

- 새 기술 도입 전에 **`docs/adr/` 에 ADR 을 먼저 작성**한다.
  형식: `해결하려는 문제 → 대안 비교 → 선택 근거 → 포기하는 것`.
- **측정 없이 최적화하지 않는다.** 샤딩·캐시·비동기화는 병목이 실측된 뒤에만.
- **성능 개선 시 Before 수치를 먼저 기록한다.** After 만 있는 개선은 근거가 없다.
- 절대 TPS 는 로컬 환경에서 주장하지 않는다. **요청당 Redis 명령 수** 같은 하드웨어 독립 비율 지표를 쓴다.

---

## 커밋

- Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `perf:`, `refactor:`, `chore:`
- 커밋 제목과 본문은 **한국어로 작성한다.** Conventional Commit 타입, 코드 식별자,
  명령어와 고유 기술명은 원문을 유지한다. 예: `feat: 판매 회차 조회 저장소 추가`.
- 불변식에 영향을 주는 변경은 본문에 해당 불변식 ID(`I-8` 등)를 명시한다.
- 푸시 전후의 변경 요약과 결과 보고, PR 제목과 본문은 **한국어로 작성한다.**
  브랜치명, 명령어, 파일명, 클래스명과 테스트명은 번역하지 않는다.
- 커밋/푸시는 사용자가 요청할 때만 한다.
