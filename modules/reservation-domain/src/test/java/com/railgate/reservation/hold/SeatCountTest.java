package com.railgate.reservation.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-2.3 "1~4석을 선택해 선점한다" 의 한 요청당 좌석 수 제한.
 *
 * 주의: 이 값 객체는 I-12(사용자가 동시에 4석 초과 보유 금지)를 보장하지 않는다.
 * 사용자가 3석 요청을 동시에 두 번 보내면 각 요청의 SeatCount 는 둘 다 유효하지만
 * 총 6석을 점유한다. I-12 는 user_hold_quota 카운터 행의 조건부 UPDATE 로 강제한다.
 */
@DisplayName("SeatCount - 한 요청의 좌석 수는 1~4석이다")
class SeatCountTest {

    @ParameterizedTest(name = "{0}석은 허용된다")
    @ValueSource(ints = {1, 2, 3, 4})
    void 좌석_수가_1에서_4_사이면_생성된다(int value) {
        assertThat(new SeatCount(value).value()).isEqualTo(value);
    }

    @Test
    void 좌석_수가_0이면_생성할_수_없다() {
        assertThatThrownBy(() -> new SeatCount(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    @Test
    void 좌석_수가_음수면_생성할_수_없다() {
        assertThatThrownBy(() -> new SeatCount(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 좌석_수가_5석_이상이면_생성할_수_없다() {
        assertThatThrownBy(() -> new SeatCount(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4");
    }

    @Test
    void 같은_값의_좌석_수는_동등하다() {
        assertThat(new SeatCount(3)).isEqualTo(new SeatCount(3));
    }
}
