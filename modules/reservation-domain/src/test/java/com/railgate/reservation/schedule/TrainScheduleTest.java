package com.railgate.reservation.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.saleevent.SaleEventId;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 운행편과 판매 회차의 소속 계약 (TASK-002G-D §4).
 *
 * <p>quota 범위가 판매 회차 단위이므로, 소속이 바뀌면 이미 쌓인 quota 의 의미가 달라진다.
 *
 * <p><b>이 테스트가 고정하는 범위를 분명히 해 둔다.</b>
 * <ul>
 *   <li>도메인 객체 <b>인스턴스</b>는 불변이다</li>
 *   <li><b>공개 재배정 API 를 제공하지 않는다</b></li>
 * </ul>
 *
 * <p>같은 {@link TrainScheduleId} 의 <b>영속 소속</b>이 다른 {@code SaleEventId} 로 갱신되는 것은
 * <b>여기서 막지 못하며 테스트 범위도 아니다.</b> 도메인은 이전에 저장된 값을 알지 못한다.
 * 그 최종 방어는 Task 2G-E-B 의 repository/DB 계약이다.
 */
@DisplayName("TrainSchedule - 판매 회차 소속 계약")
class TrainScheduleTest {

    private static final TrainScheduleId SCHEDULE_ID = new TrainScheduleId(101L);
    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final SaleEventId SEOLLAL = new SaleEventId(9002L);

    @Nested
    @DisplayName("편성")
    class 편성 {

        @Test
        void 운행편은_정확히_하나의_판매_회차에_속한다() {
            TrainSchedule schedule = TrainSchedule.of(SCHEDULE_ID, CHUSEOK);

            assertThat(schedule.id()).isEqualTo(SCHEDULE_ID);
            assertThat(schedule.saleEventId()).isEqualTo(CHUSEOK);
            assertThat(schedule.belongsTo(CHUSEOK)).isTrue();
            assertThat(schedule.belongsTo(SEOLLAL)).isFalse();
        }

        @Test
        void 소속_없는_운행편은_만들_수_없다() {
            assertThatThrownBy(() -> TrainSchedule.of(SCHEDULE_ID, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 식별자_없는_운행편은_만들_수_없다() {
            assertThatThrownBy(() -> TrainSchedule.of(null, CHUSEOK))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * 같은 회차의 여러 운행편은 하나의 quota 범위로 모인다 (TASK-002G-D §8).
         * 이것이 "운행편만 바꿔서 상한을 우회" 를 막는 지점이다.
         */
        @Test
        void 같은_회차의_여러_운행편은_같은_quota_범위를_가리킨다() {
            TrainSchedule a = TrainSchedule.of(new TrainScheduleId(101L), CHUSEOK);
            TrainSchedule b = TrainSchedule.of(new TrainScheduleId(202L), CHUSEOK);

            assertThat(a.id()).isNotEqualTo(b.id());
            assertThat(a.saleEventId()).isEqualTo(b.saleEventId());
        }

        /**
         * 운행편 식별자를 판매 회차 식별자로 암묵 대입하지 않는다 (TASK-002G-D §8).
         * 두 값이 우연히 같은 숫자여도 서로 다른 타입이므로 섞이지 않는다.
         */
        @Test
        void 운행편_식별자와_판매_회차_식별자는_다른_타입이다() {
            TrainSchedule schedule = TrainSchedule.of(new TrainScheduleId(9001L), CHUSEOK);

            assertThat((Object) schedule.id()).isNotEqualTo(schedule.saleEventId());
            assertThat(schedule.id().value()).isEqualTo(schedule.saleEventId().value());
        }
    }

    /**
     * 인스턴스 불변과 공개 재배정 API 부재.
     *
     * <p><b>이 검사들은 aggregate identity 수준의 소속 불변을 증명하지 않는다.</b>
     * 증명하는 것은 "이 객체를 통해 소속을 바꿀 수 없다" 는 것뿐이다.
     * 같은 {@code TrainScheduleId} 의 영속 소속이 갱신되는 것은 저장소의 책임이다 (2G-E-B).
     *
     * <p>검사를 두는 목적은 "지금 그렇다" 가 아니라 <b>"앞으로도 그래야 한다"</b> 를 고정하는
     * 것이다. 나중에 누군가 {@code reassign(SaleEventId)} 를 추가하면 여기서 깨진다.
     */
    @Nested
    @DisplayName("인스턴스 불변과 재배정 API 부재")
    class 인스턴스_불변 {

        @Test
        void 모든_필드가_final_이다() {
            assertThat(Arrays.stream(TrainSchedule.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toList())
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s 는 final 이어야 한다", field.getName())
                            .isTrue());
        }

        @Test
        void 상태를_바꾸는_public_메서드가_없다() {
            assertThat(Arrays.stream(TrainSchedule.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getReturnType() == void.class)
                    .map(Method::getName)
                    .toList())
                    .as("변경 메서드는 재배정 통로가 되므로 두지 않는다")
                    .isEmpty();
        }

        @Test
        void 판매_회차_식별자를_받는_인스턴스_메서드는_질의뿐이다() {
            assertThat(Arrays.stream(TrainSchedule.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> Arrays.asList(method.getParameterTypes())
                            .contains(SaleEventId.class))
                    .map(Method::getName)
                    .toList())
                    .containsExactly("belongsTo");
        }
    }

    @Nested
    @DisplayName("복원")
    class 복원 {

        @Test
        void 저장된_운행편을_복원한다() {
            TrainSchedule schedule = TrainSchedule.restore(
                    new TrainScheduleSnapshot(SCHEDULE_ID, CHUSEOK));

            assertThat(schedule.id()).isEqualTo(SCHEDULE_ID);
            assertThat(schedule.belongsTo(CHUSEOK)).isTrue();
        }

        /**
         * 소속 없는 행은 그 운행편의 좌석이 어느 quota 카운터에도 귀속되지 않는다는 뜻이다.
         * 조회 시점에 즉시 실패시킨다.
         */
        @Test
        void 소속_없는_행은_복원을_거부한다() {
            assertThatThrownBy(() -> new TrainScheduleSnapshot(SCHEDULE_ID, null))
                    .isInstanceOf(TrainScheduleRestoreException.class);
        }

        @Test
        void 식별자_없는_행은_복원을_거부한다() {
            assertThatThrownBy(() -> new TrainScheduleSnapshot(null, CHUSEOK))
                    .isInstanceOf(TrainScheduleRestoreException.class);
        }

        @Test
        void 스냅샷이_없으면_복원을_거부한다() {
            assertThatThrownBy(() -> TrainSchedule.restore(null))
                    .isInstanceOf(TrainScheduleRestoreException.class);
        }

        /**
         * 복원은 스냅샷에 기록된 소속을 그대로 옮긴다.
         *
         * <p><b>이 테스트는 "소속을 바꿀 수 없다" 를 주장하지 않는다.</b> 도메인은 이전에
         * 저장된 값을 알지 못하므로, 다른 소속이 담긴 스냅샷이 오면 그대로 복원한다.
         * 여기서 고정하는 것은 <b>복원이 값을 왜곡하지 않는다</b> 는 것뿐이다.
         */
        @Test
        void 복원한_인스턴스는_snapshot의_소속을_유지한다() {
            TrainScheduleSnapshot snapshot = new TrainScheduleSnapshot(SCHEDULE_ID, CHUSEOK);

            TrainSchedule schedule = TrainSchedule.restore(snapshot);

            assertThat(schedule.saleEventId()).isEqualTo(snapshot.saleEventId());
            assertThat(schedule.id()).isEqualTo(snapshot.id());
        }

        /** 생성 → 스냅샷 → 복원 왕복. 저장소가 생기면 이 왕복이 곧 저장·조회 경로가 된다. */
        @Test
        void 생성한_운행편을_스냅샷으로_왕복해도_값이_같다() {
            TrainSchedule origin = TrainSchedule.of(SCHEDULE_ID, CHUSEOK);

            TrainSchedule restored = TrainSchedule.restore(
                    new TrainScheduleSnapshot(origin.id(), origin.saleEventId()));

            assertThat(restored.id()).isEqualTo(origin.id());
            assertThat(restored.saleEventId()).isEqualTo(origin.saleEventId());
            assertThat(restored.belongsTo(CHUSEOK)).isTrue();
        }
    }
}
