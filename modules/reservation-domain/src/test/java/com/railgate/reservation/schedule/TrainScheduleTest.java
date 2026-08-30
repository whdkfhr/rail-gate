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
 * <p>quota 범위가 판매 회차 단위이므로, <b>소속이 바뀌면 이미 쌓인 quota 의 의미가 달라진다.</b>
 * 그 사용자가 어느 회차에서 몇 석을 잡고 있었는지 되짚을 수 없다.
 * 이 테스트는 소속을 바꿀 <b>수단 자체가 없다</b>는 것을 고정한다.
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
     * <b>소속 불변을 상태 규칙이 아니라 구조로 강제한다.</b>
     *
     * <p>"판매 시작 후에는 못 바꾼다" 는 규칙을 고르지 않은 이유는, 좌석 재고와 quota 행이
     * 회차가 열리기 전에도 만들어질 수 있어 오픈 전 재배정도 안전하지 않기 때문이다.
     * 그래서 바꿀 수단 자체를 두지 않았다.
     */
    @Nested
    @DisplayName("소속 불변")
    class 소속_불변 {

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
                    .as("소속을 바꾸는 통로가 되므로 변경 메서드를 두지 않는다")
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

        @Test
        void 복원해도_소속을_바꿀_수_없다() {
            TrainSchedule schedule = TrainSchedule.restore(
                    new TrainScheduleSnapshot(SCHEDULE_ID, CHUSEOK));

            TrainSchedule reassigned = TrainSchedule.restore(
                    new TrainScheduleSnapshot(SCHEDULE_ID, SEOLLAL));

            // 재배정은 "다른 객체" 를 만들 뿐 기존 객체를 바꾸지 못한다.
            assertThat(schedule.belongsTo(CHUSEOK)).isTrue();
            assertThat(reassigned.belongsTo(SEOLLAL)).isTrue();
        }
    }
}
