package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션 경계 계약 (Task 2F).
 *
 * <h2>이 테스트가 고정하는 것</h2>
 *
 * <p>이전 구현은 저장소가 자체 트랜잭션을 열고 부분 실패 시 {@code setRollbackOnly()} 를
 * 호출했다. 외부 트랜잭션에 참여하면 그것이 <b>남의 트랜잭션 전체를 rollback-only 로</b>
 * 만들어, 저장소는 정상 결과값을 돌려주는데 외부 커밋에서
 * {@link UnexpectedRollbackException} 이 터졌다.
 *
 * <p>재현 결과(Task 2F RED):
 * <pre>
 *   holdAll(...)        → SEAT_UNAVAILABLE 반환 (정상 결과처럼 보임)
 *   외부 트랜잭션 commit → UnexpectedRollbackException
 *                          "Transaction silently rolled back because it has been
 *                           marked as rollback-only"
 * </pre>
 *
 * <p>지금 계약은 다르다. 좌석 경합은 {@link SeatUnavailableException} 으로 전달되고,
 * 롤백은 경계를 소유한 호출자에게서 일어난다.
 *
 * <p><b>"예외가 나지 않는다" 와 "UnexpectedRollbackException 이 나지 않는다" 는 다르다.</b>
 * 경합 시 {@link SeatUnavailableException} 은 정상적으로 전달되어야 한다. 금지되는 것은
 * 그것이 기술 예외로 바뀌는 것이다.
 */
@Timeout(60)
@DisplayName("JdbcMultiSeatHoldRepository - 트랜잭션 경계 계약 (Task 2F)")
class MultiSeatHoldTransactionBoundaryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId OTHER_HOLD = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId USER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);

    /**
     * 좌석 선점과 <b>같은 트랜잭션</b>에서 일어나는 부수 DB 변경을 흉내내는 테스트 전용 테이블.
     *
     * <p>앞으로 {@code user_hold_quota} 나 {@code seat_state_log} 가 놓일 자리다.
     * 이번 Task 는 그것들을 구현하지 않으므로 <b>운영 마이그레이션을 추가하지 않고</b>
     * 테스트 안에서만 만든다. DDL 은 MySQL 에서 암묵적 커밋을 일으키므로
     * 반드시 트랜잭션 <b>밖</b>에서 실행한다.
     */
    private static final String SIDE_EFFECT_TABLE = "task2f_side_effect";

    private JdbcMultiSeatHoldRepository repository;
    private JdbcSeatHoldRepository singleSeatRepository;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        jdbc().execute("CREATE TABLE IF NOT EXISTS " + SIDE_EFFECT_TABLE + " ("
                + "hold_id CHAR(36) NOT NULL PRIMARY KEY, note VARCHAR(64) NOT NULL)");
        jdbc().update("DELETE FROM " + SIDE_EFFECT_TABLE);

        repository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        singleSeatRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatIds = new ArrayList<>();
        for (String seatNo : List.of("1A", "2A", "3A")) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, seatNo));
        }
    }

    private List<SeatId> allThree() {
        return seatIds.stream().map(SeatId::new).toList();
    }

    private long heldByRequester() {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE hold_id = ? AND status = 'HELD'",
                Long.class, HOLD.asString());
    }

    private long sideEffectRows() {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + SIDE_EFFECT_TABLE, Long.class);
    }

    private void recordSideEffect() {
        jdbc().update("INSERT INTO " + SIDE_EFFECT_TABLE + " (hold_id, note) VALUES (?, ?)",
                HOLD.asString(), "quota 증가가 놓일 자리");
    }

    @Nested
    @DisplayName("1. 트랜잭션 없는 직접 호출")
    class 트랜잭션_없는_호출 {

        @Test
        void 트랜잭션이_없으면_UPDATE_전에_거부한다() {
            assertThatThrownBy(() -> repository.holdAll(allThree(), HOLD, USER))
                    .as("좌석 경합이 아니라 호출자의 배선 실수다")
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(SeatUnavailableException.class);
        }

        @Test
        void 거부되면_어떤_좌석도_변경되지_않는다() {
            assertThatThrownBy(() -> repository.holdAll(allThree(), HOLD, USER))
                    .isInstanceOf(IllegalStateException.class);

            for (long id : seatIds) {
                assertThat(statusOf(id)).isEqualTo("AVAILABLE");
                assertThat(holdIdOf(id)).isNull();
                assertThat(versionOf(id)).as("UPDATE 자체가 실행되지 않았다").isZero();
            }
        }

        @Test
        void 부분_선점이_autocommit_으로_남지_않는다() {
            // 가운데 좌석을 남이 잡은 상태 = 벌크 UPDATE 가 2석만 갱신하게 되는 조건.
            // 트랜잭션이 없으면 그 2석이 autocommit 으로 확정돼 되돌릴 수단이 없다.
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> repository.holdAll(allThree(), HOLD, USER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(heldByRequester()).as("요청자가 점유한 좌석").isZero();
            assertThat(statusOf(seatIds.get(0))).isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIds.get(2))).isEqualTo("AVAILABLE");
        }
    }

    @Nested
    @DisplayName("2. 외부 트랜잭션 — 전체 성공 후 commit")
    class 전체_성공_커밋 {

        @Test
        void 커밋하면_요청_좌석_전부가_HELD_가_된다() {
            inTransaction(() -> repository.holdAll(allThree(), HOLD, USER));

            assertThat(heldByRequester()).isEqualTo(3);
            for (long id : seatIds) {
                assertThat(statusOf(id)).isEqualTo("HELD");
            }
        }

        @Test
        void 홀드_필드가_일관되게_기록된다() {
            inTransaction(() -> repository.holdAll(allThree(), HOLD, USER));

            for (long id : seatIds) {
                assertThat(holdIdOf(id)).isEqualTo(HOLD.asString());
                assertThat(heldByOf(id)).isEqualTo(USER.value());
                assertThat(heldAtOf(id)).as("held_at").isNotNull();
                assertThat(secondsUntilExpiry(id))
                        .isBetween(HOLD_DURATION.toSeconds() - 5, HOLD_DURATION.toSeconds());
            }
        }

        @Test
        void version_이_정확히_1_증가한다() {
            inTransaction(() -> repository.holdAll(allThree(), HOLD, USER));

            for (long id : seatIds) {
                assertThat(versionOf(id)).isEqualTo(1L);
            }
        }
    }

    @Nested
    @DisplayName("3. ★ 외부 트랜잭션 — 일부 좌석 경합")
    class 일부_경합 {

        @Test
        void 좌석_경합_전용_예외가_전달된다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> inTransaction(
                    () -> repository.holdAll(allThree(), HOLD, USER)))
                    .isInstanceOf(SeatUnavailableException.class);
        }

        @Test
        void UnexpectedRollbackException_으로_바뀌지_않는다() {
            // Task 2F 이전 구현이 실패하던 지점이다.
            // 경합 예외가 전달되는 것은 정상이고, 기술 예외로 바뀌는 것이 금지된다.
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            Throwable thrown = catchThrowable(() -> inTransaction(
                    () -> repository.holdAll(allThree(), HOLD, USER)));

            assertThat(thrown)
                    .as("경합은 전용 예외로 전달된다")
                    .isInstanceOf(SeatUnavailableException.class);

            // 원인 사슬 어디에도 기술 예외가 없어야 한다.
            // (AssertJ 의 rootCause() 는 원인이 아예 없으면 실패하므로 직접 순회한다.)
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                assertThat(t)
                        .as("원인 사슬에 기술 예외가 섞이면 안 된다")
                        .isNotInstanceOf(UnexpectedRollbackException.class);
            }
        }

        @Test
        void 요청자가_부분적으로_잡은_좌석은_0개다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> inTransaction(
                    () -> repository.holdAll(allThree(), HOLD, USER)))
                    .isInstanceOf(SeatUnavailableException.class);

            assertThat(heldByRequester()).isZero();
            assertThat(statusOf(seatIds.get(0))).isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIds.get(2))).isEqualTo("AVAILABLE");
        }

        @Test
        void 다른_사용자의_기존_홀드는_유지된다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> inTransaction(
                    () -> repository.holdAll(allThree(), HOLD, USER)))
                    .isInstanceOf(SeatUnavailableException.class);

            assertThat(statusOf(seatIds.get(1))).isEqualTo("HELD");
            assertThat(holdIdOf(seatIds.get(1))).isEqualTo(OTHER_HOLD.asString());
            assertThat(heldByOf(seatIds.get(1))).isEqualTo(OTHER_USER.value());
        }
    }

    @Nested
    @DisplayName("4. 성공 후 외부 rollback")
    class 성공_후_롤백 {

        @Test
        void 저장소_작업_자체는_성공한다() {
            TransactionTemplate tx = newTransactionTemplate();

            tx.executeWithoutResult(status -> {
                repository.holdAll(allThree(), HOLD, USER);

                // 아직 커밋 전이지만 같은 트랜잭션 안에서는 보인다.
                assertThat(heldByRequester()).as("트랜잭션 내부 관측").isEqualTo(3);
                status.setRollbackOnly();
            });
        }

        @Test
        void 외부가_롤백하면_좌석_변경이_반영되지_않는다() {
            TransactionTemplate tx = newTransactionTemplate();

            tx.executeWithoutResult(status -> {
                repository.holdAll(allThree(), HOLD, USER);
                status.setRollbackOnly();
            });

            assertThat(heldByRequester()).isZero();
            for (long id : seatIds) {
                assertThat(statusOf(id)).isEqualTo("AVAILABLE");
                assertThat(versionOf(id)).isZero();
            }
        }
    }

    @Nested
    @DisplayName("6. ★ 호출자가 외부 트랜잭션 안에서 예외를 잡는 경우")
    class 내부에서_예외를_잡는_경우 {

        /**
         * 호출자가 경합 예외를 <b>트랜잭션 경계 안에서</b> 잡고 계속 진행하는 시나리오.
         *
         * <p>예외 전파에만 의존하면 부분 갱신이 되돌아가지 않는다. 저장소가 자신의
         * 벌크 UPDATE 를 <b>스스로</b> 되돌려야 이 경우에도 I-9 가 유지된다.
         */
        @Test
        void 예외를_잡고_커밋해도_요청자_부분_선점은_0개다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 호출자가 경합을 정상 흐름으로 처리하고 트랜잭션을 이어간다.
                }
            });

            assertThat(heldByRequester()).as("요청자가 점유한 좌석").isZero();
            assertThat(statusOf(seatIds.get(0))).as("첫 좌석").isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIds.get(2))).as("셋째 좌석").isEqualTo("AVAILABLE");
        }

        @Test
        void 예외를_잡아도_다른_사용자의_기존_홀드는_유지된다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 무시하고 진행
                }
            });

            assertThat(statusOf(seatIds.get(1))).isEqualTo("HELD");
            assertThat(holdIdOf(seatIds.get(1))).isEqualTo(OTHER_HOLD.asString());
            assertThat(heldByOf(seatIds.get(1))).isEqualTo(OTHER_USER.value());
        }

        @Test
        void 예외를_잡으면_외부_트랜잭션은_정상_커밋할_수_있다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            // 커밋 시점에 UnexpectedRollbackException 이 나면 실패다.
            assertThatCode(() -> inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 무시하고 진행
                }
            })).doesNotThrowAnyException();
        }

        @Test
        void savepoint_이전에_기록한_변경은_롤백되지_않는다() {
            // savepoint 의 정의적 성질이다. 롤백 범위가 savepoint 이후로 한정되지 않으면
            // 저장소 호출 전에 호출자가 해둔 작업까지 날아간다.
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                recordSideEffect();                       // savepoint 이전 작업
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 같은 트랜잭션 안에서 아직 보여야 한다.
                    assertThat(sideEffectRows())
                            .as("savepoint 롤백이 이전 작업까지 되돌리면 안 된다")
                            .isEqualTo(1);
                }
            });

            assertThat(sideEffectRows()).as("커밋 후에도 유지").isEqualTo(1);
            assertThat(heldByRequester()).as("좌석만 되돌아간다").isZero();
        }

        @Test
        void 예외를_잡은_뒤_기록한_부수_변경은_커밋된다() {
            // savepoint 롤백이 외부 트랜잭션 전체를 rollback-only 로 만들지 않았다는 증거다.
            // 만들었다면 이 INSERT 는 사라지거나 커밋이 터진다.
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    recordSideEffect();
                }
            });

            assertThat(sideEffectRows()).as("예외를 잡은 뒤의 쓰기는 살아남는다").isEqualTo(1);
            assertThat(heldByRequester()).as("좌석은 여전히 0개").isZero();
        }
    }

    @Nested
    @DisplayName("5. 같은 트랜잭션의 부수 DB 변경과 원자성")
    class 부수_변경_원자성 {

        @Test
        void 성공하면_좌석과_부수_변경이_함께_커밋된다() {
            inTransaction(() -> {
                repository.holdAll(allThree(), HOLD, USER);
                recordSideEffect();
            });

            assertThat(heldByRequester()).isEqualTo(3);
            assertThat(sideEffectRows()).as("같은 트랜잭션의 부수 변경").isEqualTo(1);
        }

        @Test
        void 경합하면_좌석_부분_변경과_부수_변경이_함께_롤백된다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> inTransaction(() -> {
                // 부수 변경을 먼저 기록한 뒤 좌석 선점에서 경합이 나는 순서.
                // 좌석만 되돌아가고 부수 변경이 남으면 원자성이 깨진 것이다.
                recordSideEffect();
                repository.holdAll(allThree(), HOLD, USER);
            }))
                    .isInstanceOf(SeatUnavailableException.class);

            assertThat(heldByRequester()).as("좌석").isZero();
            assertThat(sideEffectRows()).as("부수 변경도 함께 롤백").isZero();
        }
    }
}
