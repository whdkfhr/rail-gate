package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * 단일 좌석 조건부 선점의 기능 검증.
 *
 * <p>동시성 검증은 {@code SeatHoldConcurrencyTest} 와 {@code SeatHoldRaceReproductionTest} 가 담당한다.
 */
@Timeout(60)
@DisplayName("JdbcSeatHoldRepository - 단일 좌석 조건부 선점")
class JdbcSeatHoldRepositoryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    /**
     * 16진 문자(a~f)가 확실히 포함된 고정 UUID.
     * 숫자만 있는 UUID 로는 대소문자 표현 차이를 검증할 수 없다.
     * 아래 두 상수는 <b>문자열 표현만 다를 뿐 같은 UUID 값</b>이다.
     */
    private static final String HOLD_UUID_LOWER = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    private static final String HOLD_UUID_UPPER = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";

    /** 값 자체가 다른 UUID. 표현 차이가 아니라 실제로 남의 홀드다. */
    private static final String OTHER_HOLD_UUID = "11111111-2222-4333-8444-555555555555";

    private JdbcSeatHoldRepository repository;
    private long seatId;

    @BeforeEach
    void setUp() {
        repository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    /** 주어진 문자열 표현 그대로 hold_id 를 조회한다. HoldId 를 거치면 표현이 정규화되어 검증이 무의미해진다. */
    private Long countByHoldIdLiteral(String holdIdText) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE id = ? AND hold_id = ?",
                Long.class, seatId, holdIdText);
    }

    @Nested
    @DisplayName("선점 성공")
    class 선점_성공 {

        @Test
        void 판매_가능한_좌석을_선점하면_성공한다() {
            HoldId hold = HoldId.newId();

            SeatHoldOutcome outcome = repository.hold(new SeatId(seatId), hold, new UserId(7L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.HELD);
        }

        @Test
        void 성공하면_상태가_HELD_로_바뀐다() {
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            assertThat(statusOf(seatId)).isEqualTo("HELD");
        }

        @Test
        void 성공하면_요청한_홀드_식별자가_기록된다() {
            HoldId hold = HoldId.newId();

            repository.hold(new SeatId(seatId), hold, new UserId(7L));

            assertThat(holdIdOf(seatId)).isEqualTo(hold.asString());
        }

        @Test
        void 성공하면_점유자가_기록된다() {
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            assertThat(heldByOf(seatId)).isEqualTo(7L);
        }

        @Test
        void 만료_시각은_DB_시각_기준으로_설정된다() {
            // 애플리케이션 시각이 아니라 NOW(3) 기준이어야 한다 (CLAUDE.md 규칙 7).
            // 앱 시각을 쓰면 인스턴스 간 클럭 드리프트로 만료 판정이 갈린다.
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            Long secondsUntilExpiry = jdbc().queryForObject(
                    "SELECT TIMESTAMPDIFF(SECOND, NOW(3), expires_at) FROM seat_inventory WHERE id = ?",
                    Long.class, seatId);

            assertThat(secondsUntilExpiry)
                    .as("설정한 홀드 유지 시간(5분) 근처여야 한다")
                    .isBetween(HOLD_DURATION.toSeconds() - 5, HOLD_DURATION.toSeconds());
        }

        @Test
        void 만료_시각은_밀리초_정밀도를_가진다() {
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            String expiresAt = jdbc().queryForObject(
                    "SELECT CAST(expires_at AS CHAR) FROM seat_inventory WHERE id = ?",
                    String.class, seatId);

            assertThat(expiresAt).as("DATETIME(3) 이므로 소수점 3자리가 있어야 한다").contains(".");
        }

        @Test
        void 성공하면_version_이_증가한다() {
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            Long version = jdbc().queryForObject(
                    "SELECT version FROM seat_inventory WHERE id = ?", Long.class, seatId);

            assertThat(version).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("경합 실패 (정상 동작)")
    class 경합_실패 {

        // CLAUDE.md 규칙 21: 409 는 정상 동작이며 오류율에 포함하지 않는다.
        // 그래서 예외를 던지지 않고 결과값으로 돌려준다.

        @Test
        void 이미_선점된_좌석은_다시_선점되지_않는다() {
            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(7L));

            SeatHoldOutcome outcome =
                    repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(8L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.ALREADY_HELD);
        }

        @Test
        void 경합_실패는_기존_홀드를_바꾸지_않는다() {
            HoldId winner = HoldId.newId();
            repository.hold(new SeatId(seatId), winner, new UserId(7L));

            repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(8L));

            assertThat(holdIdOf(seatId)).isEqualTo(winner.asString());
            assertThat(heldByOf(seatId)).isEqualTo(7L);
        }

        @Test
        void 결제_중인_좌석은_선점되지_않는다() {
            // AVAILABLE 행의 status 만 PAYING 으로 바꾸면 hold_id/held_by/expires_at 이 없는
            // PAYING 행이 만들어진다. 도메인이 절대 만들 수 없는 조합이며
            // (SeatSnapshot 의 PAYING 은 세 값이 모두 필수),
            // 그런 행으로 검증하면 실제로는 존재하지 않는 상황을 테스트하게 된다.
            //
            // 그래서 유효한 경로로 만든다. 선점한 뒤 확정 단계와 같은 형태의 CAS 로 승격시킨다.
            HoldId hold = HoldId.newId();
            repository.hold(new SeatId(seatId), hold, new UserId(7L));

            int promoted = jdbc().update(
                    "UPDATE seat_inventory SET status = 'PAYING' "
                            + "WHERE id = ? AND hold_id = ? AND status = 'HELD'",
                    seatId, hold.asString());
            assertThat(promoted).as("홀드 소유권을 유지한 채 PAYING 으로 승격").isEqualTo(1);

            SeatHoldOutcome outcome =
                    repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(8L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.ALREADY_HELD);
        }

        @Test
        void 결제_중인_좌석은_홀드_정보를_유지한다() {
            // 위 픽스처가 유효한 도메인 상태인지 자체를 확인한다.
            // PAYING 은 hold_id / held_by / expires_at 이 모두 있어야 한다.
            HoldId hold = HoldId.newId();
            repository.hold(new SeatId(seatId), hold, new UserId(7L));
            jdbc().update(
                    "UPDATE seat_inventory SET status = 'PAYING' "
                            + "WHERE id = ? AND hold_id = ? AND status = 'HELD'",
                    seatId, hold.asString());

            Long incomplete = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE id = ?
                       AND status = 'PAYING'
                       AND (hold_id IS NULL OR held_by IS NULL OR expires_at IS NULL)
                    """, Long.class, seatId);

            assertThat(incomplete).as("PAYING 인데 홀드 정보가 비어 있는 행").isZero();
        }

        @Test
        void 판매된_좌석은_선점되지_않는다() {
            // 이 픽스처는 PAYING 과 달리 유효한 조합이다.
            // 도메인의 SOLD 는 hold_id / held_by / expires_at 이 모두 없고 reservation_id 만 있다
            // (확정이 홀드를 소멸시키기 때문). AVAILABLE 행에서 시작하므로 세 값이 이미 NULL 이고,
            // reservation_id 만 채우면 그 조합과 정확히 일치한다.
            jdbc().update(
                    "UPDATE seat_inventory SET status = 'SOLD', reservation_id = 100 WHERE id = ?",
                    seatId);

            SeatHoldOutcome outcome =
                    repository.hold(new SeatId(seatId), HoldId.newId(), new UserId(8L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.ALREADY_HELD);
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
        }

        @Test
        void 존재하지_않는_좌석은_선점되지_않는다() {
            SeatHoldOutcome outcome =
                    repository.hold(new SeatId(999_999L), HoldId.newId(), new UserId(8L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.ALREADY_HELD);
        }
    }

    @Nested
    @DisplayName("★ I-8 구조적 기반")
    class 스키마_불변식 {

        @Test
        void 같은_스케줄에_같은_좌석_번호를_두_번_넣을_수_없다() {
            // 이 제약이 I-8 의 근거다. 좌석당 행이 하나뿐이므로
            // "같은 좌석을 두 명이 선점" 이 "같은 행을 두 번 전이" 와 동의어가 된다.
            assertThatThrownBy(() -> insertAvailableSeat(SCHEDULE_ID, "12A"))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void 다른_스케줄이면_같은_좌석_번호를_쓸_수_있다() {
            long other = insertAvailableSeat(SCHEDULE_ID + 1, "12A");

            assertThat(other).isNotEqualTo(seatId);
        }

        @Test
        void 정의되지_않은_상태는_저장할_수_없다() {
            assertThatThrownBy(() -> jdbc().update(
                    "UPDATE seat_inventory SET status = 'BOGUS' WHERE id = ?", seatId))
                    .isInstanceOf(Exception.class);
        }

        @ParameterizedTest(name = "소문자 {0} 은 거부된다")
        @ValueSource(strings = {"available", "held", "paying", "sold", "Available", "aVaIlAbLe"})
        void 상태값은_대소문자를_구분한다(String lowercase) {
            // 테이블 기본 콜레이션(utf8mb4_0900_ai_ci)은 대소문자를 구분하지 않는다.
            // status 컬럼에 ascii_bin 을 지정하지 않으면 'available' 이 CHECK 를 통과하고,
            // 조건부 UPDATE 의 WHERE status='AVAILABLE' 도 그 행에 매칭된다.
            assertThatThrownBy(() -> jdbc().update(
                    "UPDATE seat_inventory SET status = ? WHERE id = ?", lowercase, seatId))
                    .isInstanceOf(Exception.class);
        }

        @Test
        void 소문자_상태는_저장되지_않으므로_선점_조건에도_걸리지_않는다() {
            // 위 테스트가 저장 자체를 막지만, 방어의 목적은 결국 이것이다.
            // 소문자 행이 존재할 수 있었다면 AVAILABLE 이 아닌 좌석이 선점 대상이 되었을 것이다.
            assertThatThrownBy(() -> jdbc().update(
                    "UPDATE seat_inventory SET status = 'available' WHERE id = ?", seatId))
                    .isInstanceOf(Exception.class);

            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 같은_UUID의_대소문자_표현은_DB에서_같은_홀드로_매칭된다() {
            // HoldId 는 Java UUID 를 감싼 값 객체다.
            // 문자열의 대소문자는 표현 차이일 뿐 같은 128비트 식별자이므로,
            // DB 비교 규칙도 그 값 동등성을 따라야 한다.
            assertThat(HoldId.of(HOLD_UUID_LOWER))
                    .as("Java 에서 두 표현은 같은 값이다")
                    .isEqualTo(HoldId.of(HOLD_UUID_UPPER));

            repository.hold(new SeatId(seatId), HoldId.of(HOLD_UUID_LOWER), new UserId(7L));

            // 저장은 UUID.toString() 의 소문자 표준 표현으로 이루어진다.
            assertThat(holdIdOf(seatId)).isEqualTo(HOLD_UUID_LOWER);

            Long lowerMatch = countByHoldIdLiteral(HOLD_UUID_LOWER);
            Long upperMatch = countByHoldIdLiteral(HOLD_UUID_UPPER);

            assertThat(lowerMatch).as("소문자 표현으로 조회").isEqualTo(1L);
            assertThat(upperMatch).as("대문자 표현으로 조회. 같은 UUID 이므로 매칭된다").isEqualTo(1L);
        }

        @Test
        void 대소문자_표현만_다른_UUID로도_소유권_CAS가_성공한다() {
            // 후속 Task 의 확정/해제 CAS 가 쓸 조건이다.
            //   UPDATE ... WHERE id=? AND hold_id=? AND status='HELD'
            // 소유자가 같은 UUID 를 대문자로 표기해 보내도 소유권은 성립해야 한다.
            repository.hold(new SeatId(seatId), HoldId.of(HOLD_UUID_LOWER), new UserId(7L));

            int affected = jdbc().update(
                    "UPDATE seat_inventory SET status = 'PAYING' "
                            + "WHERE id = ? AND hold_id = ? AND status = 'HELD'",
                    seatId, HOLD_UUID_UPPER);

            assertThat(affected).as("같은 UUID 이므로 소유권이 성립한다").isEqualTo(1);
            assertThat(statusOf(seatId)).isEqualTo("PAYING");
        }

        @Test
        void 완전히_다른_UUID의_소유권_CAS는_실패하고_상태를_바꾸지_않는다() {
            // 값이 다른 UUID 는 표현과 무관하게 소유권이 성립하지 않는다.
            repository.hold(new SeatId(seatId), HoldId.of(HOLD_UUID_LOWER), new UserId(7L));

            int affected = jdbc().update(
                    "UPDATE seat_inventory SET status = 'PAYING' "
                            + "WHERE id = ? AND hold_id = ? AND status = 'HELD'",
                    seatId, OTHER_HOLD_UUID);

            assertThat(affected).as("남의 홀드는 건드릴 수 없다").isZero();
            assertThat(statusOf(seatId)).isEqualTo("HELD");
            assertThat(holdIdOf(seatId)).isEqualTo(HOLD_UUID_LOWER);
        }

        @Test
        void 좌석_번호는_반대로_대소문자를_구분하지_않는다() {
            // 의도된 비대칭이다. seat_no 는 UNIQUE(schedule_id, seat_no) 의 일부이고,
            // 대소문자를 구분하면 '12a' 와 '12A' 가 서로 다른 좌석으로 등록되어
            // 같은 물리 좌석에 행이 두 개 생긴다. 그러면 I-8 의 구조적 근거가 무너진다.
            // status/hold_id 와 달리 여기서는 느슨한 비교가 더 안전하다.
            assertThatThrownBy(() -> insertAvailableSeat(SCHEDULE_ID, "12a"))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Nested
    @DisplayName("잠금 순서와 잠금 대기 설정")
    class 잠금_설정 {

        @Test
        void 홀드_UPDATE_는_기본_키_인덱스를_쓴다() {
            // CLAUDE.md 규칙 3 의 취지. 옵티마이저가 다른 인덱스를 고르면
            // 다중 좌석 UPDATE 에서 잠금 순서 보장이 사라진다.
            // 단건은 PK 조회지만, 인덱스를 추가하다 계획이 바뀌는 것을 여기서 감지한다.
            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN UPDATE seat_inventory SET status = 'HELD' "
                            + "WHERE id = " + seatId + " AND status = 'AVAILABLE'");

            assertThat(String.valueOf(plan.get("key"))).isEqualTo("PRIMARY");
        }

        @Test
        void 세션의_잠금_대기_상한이_3초로_설정된다() {
            // 기본값 50초는 경합 시 커넥션 풀을 고갈시킨다 (CLAUDE.md 규칙 9).
            Integer timeout = jdbc().queryForObject(
                    "SELECT @@session.innodb_lock_wait_timeout", Integer.class);

            assertThat(timeout).isEqualTo(lockWaitTimeoutSeconds());
        }
    }
}
