package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.hold.ConfirmSeatCommand;
import com.railgate.reservation.application.hold.ConfirmSeatService;
import com.railgate.reservation.application.hold.ReleaseHoldCommand;
import com.railgate.reservation.application.hold.ReleaseHoldService;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ★ <b>실패 후보가 배치 앞부분을 차지해도 뒤의 정상 좌석이 처리되는가.</b>
 *
 * <p>후보 조회는 {@code (expires_at, id)} 오름차순의 첫 페이지만 본다. 회수되지 않는 후보
 * (손상·quota 행 부재)는 사라지지 않으므로 <b>그 자리를 영구히 차지한다.</b> 조회 크기를
 * 채우면 다음 실행도 같은 후보만 보고 뒤의 정상 좌석에 끝내 도달하지 못한다.
 *
 * <p>그룹별 트랜잭션 격리는 <b>후보 목록 안</b>의 그룹만 서로 보호한다. 목록에 들어오지도
 * 못한 좌석은 보호 대상이 아니다 — 이 테스트가 그 구멍을 고정한다.
 */
@DisplayName("만료 배치 — 실패 후보 뒤의 정상 좌석 진행 보장")
class ExpirySweepProgressTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_700L;
    private static final UserId OWNER = new UserId(61L);
    private static final UserId ORPHAN = new UserId(62L);

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ExpireHoldsService service;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private ConfirmSeatService confirmSeat;

    @Autowired
    private com.railgate.reservation.expiry.SeatExpiryPort expiryPort;

    @Autowired
    private com.railgate.reservation.quota.UserHoldQuotaPort quotaPort;

    @Autowired
    private org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager;

    /**
     * 실제 서비스 클래스 + 실제 관리자 + 데코레이터 포트. 백오프는 대기하지 않는다.
     *
     * <p>{@code ExpireHoldsService} 는 {@code @Transactional} 프록시가 아니라
     * {@code TransactionTemplate} 으로 경계를 여므로, 손으로 조립해도 트랜잭션 동작이 같다.
     */
    private ExpireHoldsService handWired(com.railgate.reservation.expiry.SeatExpiryPort decorated) {
        return new ExpireHoldsService(decorated, quotaPort, transactionManager, attempt -> { });
    }

    /**
     * ★ <b>첫 페이지를 실제로 조회한 직후</b> 훅을 한 번만 실행하는 데코레이터.
     *
     * <p>운영 저장소에 위임해 진짜 후보를 읽고, <b>반환 전에</b> 훅을 돌린다. 그래야
     * "탐색 중 변경" 이 실제 교차 실행이 된다 — 훅을 sweep 앞에 두면 탐색 <b>전</b> 변경일 뿐이다.
     * {@code Thread.sleep} 이나 타이밍에 의존하지 않고 호출 순서로 고정한다.
     */
    private com.railgate.reservation.expiry.SeatExpiryPort afterFirstPage(
            com.railgate.reservation.expiry.SeatExpiryPort delegate,
            java.util.concurrent.atomic.AtomicInteger pageCalls,
            java.util.concurrent.atomic.AtomicInteger hookRuns,
            Runnable hook) {
        return new com.railgate.reservation.expiry.SeatExpiryPort() {
            @Override
            public List<com.railgate.reservation.expiry.ExpiredSeat> findExpiredSeats(
                    int pageSize, java.util.Optional<com.railgate.reservation.expiry.ExpiryCursor> after) {
                List<com.railgate.reservation.expiry.ExpiredSeat> found =
                        delegate.findExpiredSeats(pageSize, after);
                if (pageCalls.incrementAndGet() == 1) {
                    hookRuns.incrementAndGet();
                    hook.run();
                }
                return found;
            }

            @Override
            public int expire(List<com.railgate.reservation.expiry.ExpiryCandidate> candidates) {
                return delegate.expire(candidates);
            }
        };
    }

    private static List<SeatId> ids(long... values) {
        List<SeatId> list = new ArrayList<>();
        for (long v : values) {
            list.add(new SeatId(v));
        }
        return list;
    }

    /** 만료 시각을 명시해 순서를 고정한다. 초 단위 차이면 인덱스 정렬이 결정적이다. */
    private void expireSecondsAgo(long seatId, int seconds) {
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL ? SECOND) WHERE id = ?",
                seconds, seatId);
    }

    /** 선점 경로를 거치지 않은 만료 HELD. quota 행이 없다 — backfill 안 된 홀드를 흉내 낸다. */
    private void expiredHeldWithoutQuota(UserId user, long seatId, int secondsAgo) {
        jdbc().update("""
                UPDATE seat_inventory
                   SET status = 'HELD', hold_id = ?, held_by = ?, held_at = NOW(3),
                       expires_at = DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 WHERE id = ?
                """, HoldId.newId().asString(), user.value(), secondsAgo, seatId);
    }

    @Nested
    @DisplayName("§1 ★★ 손상 후보가 앞을 막는 경우")
    class 손상_후보가_앞을_막는다 {

        /**
         * ★★ 오래된 손상 좌석 1석 + 그보다 나중에 만료된 정상 좌석 1석, 조회 크기 1.
         *
         * <p>손상 좌석은 회수되지 않으므로 계속 첫 후보다. 여러 번 실행해도 정상 좌석은
         * 후보 목록에 들어오지 못한다.
         */
        @Test
        void 손상_좌석_뒤의_정상_좌석이_여러_번_실행해도_회수된다() {
            long broken = insertAvailableSeat(SCHEDULE, "1A");
            long normal = insertAvailableSeat(SCHEDULE, "1B");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(broken, normal)));
            expireSecondsAgo(broken, 60);    // 더 오래됨 → 항상 첫 후보
            expireSecondsAgo(normal, 10);
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?", broken);

            for (int run = 1; run <= 3; run++) {
                service.sweep(1);
            }

            assertThat(statusOf(broken)).as("손상 좌석은 회수하지 않는다 — 데이터 보존").isEqualTo("HELD");
            assertThat(statusOf(normal))
                    .as("★★ 손상 좌석 뒤에 있어도 정상 좌석은 회수돼야 한다")
                    .isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value()))
                    .as("★★ 회수된 1석만큼 줄어 1 (손상 좌석 몫은 남는다)")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§2 ★★ quota 행 부재 후보가 앞을 막는 경우")
    class quota_부재_후보가_앞을_막는다 {

        /** ★★ 다른 사용자의 quota 없는 오래된 좌석이 첫 후보를 차지한다. */
        @Test
        void quota_행_없는_좌석_뒤의_정상_좌석이_여러_번_실행해도_회수된다() {
            long orphan = insertAvailableSeat(SCHEDULE, "1A");
            long normal = insertAvailableSeat(SCHEDULE, "1B");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(normal)));
            expireSecondsAgo(normal, 10);
            expiredHeldWithoutQuota(ORPHAN, orphan, 60);   // 더 오래됨 → 항상 첫 후보

            for (int run = 1; run <= 3; run++) {
                service.sweep(1);
            }

            assertThat(statusOf(orphan)).as("quota 없는 좌석은 회수하지 않는다").isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value()))
                    .as("행을 만들지 않는다").isEqualTo(-1);
            assertThat(statusOf(normal))
                    .as("★★ 실패 후보 뒤에 있어도 정상 좌석은 회수돼야 한다")
                    .isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§3 ★★ 실패 후보가 조회 크기보다 많은 경우")
    class 실패_후보가_조회_크기보다_많다 {

        /**
         * ★★ 손상 3석(조회 크기 1보다 많다) + 그 뒤 정상 1석.
         *
         * <p>페이지 예산이 충분하면 <b>한 번의 호출</b>로 지나간다.
         */
        @Test
        void 페이지_예산이_충분하면_한_번에_뒤의_정상_좌석까지_간다() {
            List<Long> broken = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                long id = insertAvailableSeat(SCHEDULE, "B" + i);
                expiredHeldWithoutQuota(ORPHAN, id, 60 - i);   // 60·59·58초 전 — 가장 오래됨
                broken.add(id);
            }
            long normal = insertAvailableSeat(SCHEDULE, "1A");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(normal)));
            expireSecondsAgo(normal, 10);

            ExpirySweepResult result = service.sweep(
                    ExpirySweepRequest.fromStart(1, 8));

            assertThat(result.pagesRead()).as("페이지를 넘어갔다").isGreaterThan(1);
            assertThat(statusOf(normal)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
            broken.forEach(id -> assertThat(statusOf(id))
                    .as("실패 후보의 데이터는 보존된다").isEqualTo("HELD"));
            assertThat(result.failed()).as("실패는 계속 보고된다").isNotEmpty();
        }

        /**
         * ★★ 예산이 부족하면 한 번에 못 가지만, {@code nextCursor} 로 <b>이어가면</b> 도달한다.
         *
         * <p>커서를 결과에 싣기만 하고 소비하지 않으면 진행 보장이 아니다 — 여기서 실제로 소비한다.
         */
        @Test
        void 예산이_부족하면_nextCursor_로_이어가서_도달한다() {
            for (int i = 0; i < 3; i++) {
                long id = insertAvailableSeat(SCHEDULE, "B" + i);
                expiredHeldWithoutQuota(ORPHAN, id, 60 - i);
            }
            long normal = insertAvailableSeat(SCHEDULE, "1A");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(normal)));
            expireSecondsAgo(normal, 10);

            ExpirySweepRequest request = ExpirySweepRequest.fromStart(1, 1);   // 한 번에 1페이지만
            ExpirySweepResult first = service.sweep(request);

            assertThat(first.hasMore()).as("예산 소진 — 뒤에 더 있다").isTrue();
            assertThat(statusOf(normal)).as("아직 도달하지 못했다").isEqualTo("HELD");

            // ★ 커서를 실제로 소비해 이어간다. 유한한 횟수 안에 끝나야 한다.
            ExpirySweepResult last = first;
            int calls = 1;
            while (last.hasMore() && calls < 10) {
                last = service.sweep(request.continueAfter(last.nextCursor().orElseThrow()));
                calls++;
            }

            assertThat(last.hasMore()).as("순회가 끝났다").isFalse();
            assertThat(calls).as("유한한 호출 안에").isLessThan(10);
            assertThat(statusOf(normal)).as("★★ 이어가기로 정상 좌석에 도달했다").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
        }

        /**
         * ★★ <b>실패 후보가 실행 예산을 채우면 {@code sweep(int)} 반복만으로는 못 간다.</b>
         *
         * <p>{@code sweep(int)} 는 매번 처음부터 조회한다. 실패 후보가
         * {@code pageSize × DEFAULT_MAX_PAGES} 를 채우면 매 호출이 같은 후보만 다시 읽고
         * 뒤의 정상 좌석에 끝내 도달하지 못한다. <b>continuation 을 소비해야 진행된다</b> —
         * 이 테스트가 두 호출 흐름의 차이를 고정한다.
         */
        @Test
        void 예산을_채우는_실패_후보는_반복_호출로는_못_가고_continuation_으로만_간다() {
            int pageSize = 1;
            int budget = pageSize * ExpirySweepRequest.DEFAULT_MAX_PAGES;
            for (int i = 0; i < budget; i++) {           // 예산을 정확히 채우는 실패 후보
                long id = insertAvailableSeat(SCHEDULE, "B" + i);
                expiredHeldWithoutQuota(ORPHAN, id, 100 - i);
            }
            long normal = insertAvailableSeat(SCHEDULE, "1A");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(normal)));
            expireSecondsAgo(normal, 10);               // 실패 후보들보다 나중 → 항상 뒤

            // (1) 처음부터 반복 — 예산이 실패 후보로 가득 차 도달하지 못한다.
            ExpirySweepResult last = null;
            for (int run = 0; run < 3; run++) {
                last = service.sweep(pageSize);
            }
            assertThat(last.hasMore()).as("예산 소진 — 뒤에 더 있다").isTrue();
            assertThat(statusOf(normal))
                    .as("★★ sweep(int) 를 세 번 불러도 도달하지 못한다")
                    .isEqualTo("HELD");
            assertThat(last.failed()).as("매번 같은 실패 후보만 본다").hasSize(1);

            // (2) continuation 소비 — 같은 순회를 이어가 도달한다.
            ExpirySweepRequest request = ExpirySweepRequest.fromStart(pageSize);
            ExpirySweepResult step = service.sweep(request);
            int calls = 1;
            while (step.hasMore() && calls < 10) {
                step = service.sweep(request.continueAfter(step.nextCursor().orElseThrow()));
                calls++;
            }

            assertThat(step.hasMore()).as("순회 완료").isFalse();
            assertThat(calls).as("유한한 호출 안에").isLessThan(10);
            assertThat(statusOf(normal))
                    .as("★★ continuation 을 소비하면 도달한다")
                    .isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value()))
                    .as("실패 후보는 끝까지 보존된다 — 행을 만들지 않는다").isEqualTo(-1);
        }

        /** ★ 순회가 끝난 뒤 처음부터 다시 부르면 실패 후보를 <b>다시 확인</b>한다. */
        @Test
        void 순회_종료_후_처음부터_부르면_실패_후보를_다시_확인한다() {
            long orphan = insertAvailableSeat(SCHEDULE, "1A");
            expiredHeldWithoutQuota(ORPHAN, orphan, 60);
            long normal = insertAvailableSeat(SCHEDULE, "1B");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(normal)));
            expireSecondsAgo(normal, 10);

            ExpirySweepResult first = service.sweep(1);
            assertThat(first.failed()).hasSize(1);
            assertThat(statusOf(normal)).isEqualTo("AVAILABLE");

            // 실패 후보가 고쳐지지 않았으므로 다시 실패로 보고된다 — 숨겨지지 않는다.
            ExpirySweepResult second = service.sweep(1);
            assertThat(second.failed()).singleElement()
                    .satisfies(f -> assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_ROW_MISSING));

            // 고쳐 주면 다음 순회가 회수한다.
            jdbc().update("""
                    INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats)
                    VALUES (?, ?, 1)
                    """, FIXTURE_SALE_EVENT_ID, ORPHAN.value());
            ExpirySweepResult third = service.sweep(1);

            assertThat(third.failed()).as("★ 고쳐지면 회수된다").isEmpty();
            assertThat(statusOf(orphan)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value())).isZero();
        }

        /**
         * ★ 커서는 페이지의 <b>마지막</b> 행까지 전진해야 한다.
         *
         * <p>첫 행으로만 전진하면 다음 페이지가 이미 본 행을 다시 읽어 <b>같은 좌석이 후보에
         * 중복</b>된다. 중복은 벌크 UPDATE 의 튜플을 늘리지만 실제 변경 행은 하나이므로
         * {@code candidatesFound} 와 stale 집계가 어긋난다. 페이지 크기 1 에서는 첫 행과
         * 마지막 행이 같아 드러나지 않으므로 <b>크기 2</b> 로 본다.
         */
        @Test
        void 커서는_페이지_마지막까지_전진해_후보가_중복되지_않는다() {
            List<SeatId> seats = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                long id = insertAvailableSeat(SCHEDULE, "P" + i);
                seats.add(new SeatId(id));
            }
            holdSeats.hold(new HoldSeatsCommand(OWNER, seats));
            for (int i = 0; i < 4; i++) {
                expireSecondsAgo(seats.get(i).value(), 40 - i * 5);
            }

            ExpirySweepResult result = service.sweep(ExpirySweepRequest.fromStart(2, 8));

            assertThat(result.candidatesFound())
                    .as("★ 만료 좌석은 4석뿐이다 — 중복이 섞이면 이 값이 커진다").isEqualTo(4);
            assertThat(result.reclaimed()).isEqualTo(4);
            assertThat(result.staleCandidates()).as("중복이 있으면 stale 로 잘못 집계된다").isZero();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
        }

        /** ★ 같은 만료 시각의 좌석들이 페이지 경계에 걸려도 누락되지 않는다. */
        @Test
        void 같은_만료_시각이_페이지_경계에_걸려도_누락되지_않는다() {
            List<SeatId> seats = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                seats.add(new SeatId(insertAvailableSeat(SCHEDULE, "T" + i)));
            }
            holdSeats.hold(new HoldSeatsCommand(OWNER, seats));
            // 네 좌석의 expires_at 을 완전히 같게 만든다.
            jdbc().update("UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 30 SECOND) "
                    + "WHERE id IN (?, ?, ?, ?)",
                    seats.get(0).value(), seats.get(1).value(), seats.get(2).value(), seats.get(3).value());

            ExpirySweepResult result = service.sweep(ExpirySweepRequest.fromStart(1, 8));

            assertThat(result.reclaimed()).as("★ 4석 모두 회수 — id tie-breaker 가 없으면 누락된다").isEqualTo(4);
            seats.forEach(id -> assertThat(statusOf(id.value())).isEqualTo("AVAILABLE"));
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§4 ★ 실패 그룹이 먼저여도 뒤의 정상 그룹이 커밋된다")
    class 실패_그룹이_먼저인_경우 {

        /**
         * ★ quota 부족 그룹(앞) + 정상 그룹(뒤).
         *
         * <p>같은 페이지 안이라도 <b>정산 순서가 실패 그룹부터</b>인 경우를 만든다.
         * 그룹 키 오름차순이므로 사용자 id 가 작은 쪽이 먼저다.
         */
        @Test
        void 앞_그룹이_감소_거절로_실패해도_뒤_그룹은_커밋된다() {
            long failing = insertAvailableSeat(SCHEDULE, "1A");
            long ok = insertAvailableSeat(SCHEDULE, "1B");
            holdSeats.hold(new HoldSeatsCommand(OWNER, ids(failing)));    // userId 61 — 먼저
            holdSeats.hold(new HoldSeatsCommand(ORPHAN, ids(ok)));        // userId 62 — 나중
            expireSecondsAgo(failing, 60);
            expireSecondsAgo(ok, 10);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 0 WHERE user_id = ?", OWNER.value());

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.key().userId()).isEqualTo(OWNER);
                assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_DECREASE_REJECTED);
            });
            assertThat(statusOf(failing)).as("앞 그룹은 롤백").isEqualTo("HELD");
            assertThat(statusOf(ok)).as("★ 뒤 그룹은 커밋됐다").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value())).isZero();
            assertThat(result.reclaimed()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§5 ★ 탐색 중 데이터가 바뀌는 경우")
    class 탐색_중_데이터_변경 {

        /**
         * ★★ <b>첫 페이지를 실제로 조회한 뒤</b> 뒤쪽 후보가 확정·해제·연장된다.
         *
         * <p>변경을 sweep 앞에 몰아 두면 "탐색 전 변경" 일 뿐이다. 여기서는 데코레이터가
         * 운영 저장소로 <b>첫 페이지를 읽은 직후, 다음 페이지 조회 전에</b> 훅을 한 번만
         * 실행하도록 순서를 고정한다. 훅 실행 횟수와 이후 페이지 조회가 일어났음을 함께 검증한다.
         *
         * <p>계약은 "후보는 스냅숏이고 최종 판단은 조건부 UPDATE" 다. 따라서 훅이 바꾼 좌석은
         * 회수되지 않고 감소는 실제 {@code affected_rows} 만큼만 일어난다.
         *
         * <p><b>검증 범위.</b> 연장된 좌석은 만료 시각이 <b>미래로</b> 가므로 이번 순회의
         * 후보에서 빠진다. 그 좌석이 다시 만료됐을 때의 처리는 <b>다음 순회</b>의 몫이며
         * 여기서 검증하지 않는다.
         */
        @Test
        void 첫_페이지_조회_직후_확정_해제_연장되면_그_좌석은_회수되지_않는다() {
            long first = insertAvailableSeat(SCHEDULE, "1A");
            long confirmed = insertAvailableSeat(SCHEDULE, "1B");
            long released = insertAvailableSeat(SCHEDULE, "1C");
            long extended = insertAvailableSeat(SCHEDULE, "1D");
            HoldId holdA = holdSeats.hold(new HoldSeatsCommand(OWNER, ids(first, confirmed, extended))).holdId();
            HoldId holdB = holdSeats.hold(new HoldSeatsCommand(ORPHAN, ids(released))).holdId();
            // startPayment 는 만료 전에만 가능하다 (expires_at > NOW(3)).
            assertThat(new JdbcSeatPaymentRepository(dataSource(), Duration.ofMinutes(5))
                    .startPayment(new SeatId(confirmed), holdA))
                    .isEqualTo(com.railgate.reservation.hold.SeatPaymentOutcome.STARTED);
            expireSecondsAgo(first, 60);       // 첫 페이지에 들어갈 가장 오래된 후보
            expireSecondsAgo(confirmed, 50);
            expireSecondsAgo(released, 40);
            expireSecondsAgo(extended, 30);

            var pageCalls = new java.util.concurrent.atomic.AtomicInteger();
            var hookRuns = new java.util.concurrent.atomic.AtomicInteger();
            ExpireHoldsService sweeper = handWired(afterFirstPage(expiryPort, pageCalls, hookRuns, () -> {
                // ★ 첫 페이지(=first)를 읽은 뒤, 다음 페이지 조회 전에 실행된다.
                assertThat(confirmSeat.confirm(
                        new ConfirmSeatCommand(new SeatId(confirmed), holdA, new ReservationId(9L))))
                        .as("만료 지난 PAYING 도 확정된다 (2H-C 계약)")
                        .isEqualTo(com.railgate.reservation.hold.SeatConfirmationOutcome.CONFIRMED);
                assertThat(releaseHold.release(new ReleaseHoldCommand(holdB, ORPHAN)).releasedSeats())
                        .isEqualTo(1);
                jdbc().update("UPDATE seat_inventory SET expires_at = DATE_ADD(NOW(3), INTERVAL 1 HOUR) "
                        + "WHERE id = ?", extended);
            }));

            ExpirySweepResult result = sweeper.sweep(ExpirySweepRequest.fromStart(1, 8));

            assertThat(hookRuns.get()).as("★ 변경 훅은 정확히 한 번").isEqualTo(1);
            assertThat(pageCalls.get())
                    .as("★ 훅 이후에도 페이지 조회가 이어졌다 — 교차 실행이 실제로 일어났다")
                    .isGreaterThan(1);

            assertThat(statusOf(first)).as("정상 후보는 회수된다").isEqualTo("AVAILABLE");
            assertThat(statusOf(confirmed)).as("확정된 좌석은 되돌리지 않는다 (I-11)").isEqualTo("SOLD");
            assertThat(statusOf(released)).as("해제된 좌석은 이미 AVAILABLE").isEqualTo("AVAILABLE");
            assertThat(statusOf(extended)).as("연장된 좌석은 이번 순회의 후보가 아니다").isEqualTo("HELD");
            assertThat(result.reclaimed()).as("★★ 실제로 회수된 것은 first 1석뿐").isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value()))
                    .as("선점 3 − 확정 1 − 회수 1 = 1 (★ 연장된 좌석의 몫은 남는다)").isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value())).isZero();
        }
    }
}
