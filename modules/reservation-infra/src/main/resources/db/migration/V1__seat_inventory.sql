-- ---------------------------------------------------------------------------
-- seat_inventory — I-8 을 완성하기 위한 구조적 전제.
--
-- 이 테이블의 형태가 제공하는 것은 "좌석당 행이 하나뿐" 이라는 성질 하나다.
--
--   UNIQUE (schedule_id, seat_no) 때문에 한 좌석은 물리적으로 행이 하나뿐이다.
--   따라서 "같은 좌석을 두 명이 선점" 은 "같은 행을 AVAILABLE 에서 HELD 로 두 번 전이"
--   와 동의어가 되고, InnoDB 행 잠금이 그것을 직렬화한다.
--
--   좌석 재고를 카운터(남은 좌석 수)로 관리하면 이 성질이 사라진다.
--   카운터는 여러 트랜잭션이 같은 값을 읽고 각자 감소시킬 수 있기 때문이다.
--
-- 다만 이것만으로 I-8 이 지켜지지는 않는다.
-- 선점 단계는 조건부 UPDATE (... WHERE id=? AND status='AVAILABLE') 가 방어하고,
-- "최종적으로 한 예약에만 확정" 은 확정 단계
-- (... WHERE id=? AND hold_id=? AND status='PAYING') 와 함께 완결된다. 후속 Task 다.
--
-- 시각 컬럼은 모두 DATETIME(3) 이다. 밀리초 정밀도가 없으면 NOW(3) 기반 만료 판정에서
-- 같은 초 안의 순서를 구분할 수 없다 (CLAUDE.md 규칙 7).
-- ---------------------------------------------------------------------------

CREATE TABLE seat_inventory
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,

    -- 열차 스케줄 식별자. train_schedule 테이블은 후속 Task 에서 추가하므로
    -- 지금은 외래 키를 걸지 않는다.
    schedule_id    BIGINT      NOT NULL,
    seat_no        VARCHAR(10) NOT NULL COMMENT '사용자에게 보이는 좌석 번호. 예: 12A',

    -- CHARACTER SET ascii COLLATE ascii_bin 인 이유:
    -- 테이블 기본 콜레이션 utf8mb4_0900_ai_ci 는 대소문자를 구분하지 않는다(_ci).
    -- 그대로 두면 'available' 이 CHECK (status IN ('AVAILABLE', ...)) 를 통과하고,
    -- 조건부 UPDATE 의 WHERE status='AVAILABLE' 도 소문자 행에 매칭된다.
    -- 상태값은 사람이 읽는 문자열이 아니라 열거형이므로 바이트 단위로 비교해야 한다.
    status         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                   COMMENT 'AVAILABLE | HELD | PAYING | SOLD. 대소문자를 구분한다',

    -- 홀드 소유권. 확정/해제/만료 UPDATE 의 WHERE 조건으로 쓰인다 (CLAUDE.md 규칙 4).
    -- 이 조건이 없으면 만료 처리가 남의 홀드나 이미 확정된 예약을 건드린다 (I-11).
    --
    -- ascii_general_ci 인 이유: HoldId 는 Java UUID 를 감싼 값 객체다.
    -- UUID 문자열의 대소문자는 표현 차이일 뿐 같은 128비트 식별자이며,
    -- Java 에서 UUID.fromString 은 두 표현을 동일한 값으로 취급한다.
    -- DB 비교 규칙을 그 값 동등성과 일치시킨다.
    -- 애플리케이션은 UUID.toString() 의 소문자 표준 표현으로 저장한다.
    --
    -- status 와 규칙이 다른 이유: status 는 enum 문자열이라 표현 자체가 값이고,
    -- hold_id 는 문자열이 값의 표현일 뿐이다.
    hold_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    held_by        BIGINT      NULL COMMENT '홀드를 보유한 사용자',
    held_at        DATETIME(3) NULL,

    -- 만료 시각. 스위퍼는 status IN ('HELD','PAYING') AND expires_at <= NOW(3) 로 회수한다.
    -- SOLD 는 상태 술어에서 제외되므로 확정된 좌석은 회수되지 않는다 (I-11).
    expires_at     DATETIME(3) NULL,

    -- 최종 소유자. SOLD 의 진실의 원천이다.
    reservation_id BIGINT      NULL,

    version        BIGINT      NOT NULL DEFAULT 0,
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- ★ I-8 의 구조적 근거. 좌석당 행은 하나뿐이다.
    UNIQUE KEY uk_seat_inventory_seat (schedule_id, seat_no),

    -- 좌석 목록 조회용.
    KEY idx_seat_inventory_schedule_status (schedule_id, status),

    -- 만료 스위퍼 후보 조회용 (TASK-2 후속).
    -- 주의: 스위퍼는 이 인덱스 순서로 잠그면 안 된다. 확정 경로(PK 순서)와 교차해
    -- 데드락이 난다. 후보 SELECT 후 id 정렬하여 UPDATE 하는 2단계로 구현한다 (CLAUDE.md 규칙 2).
    KEY idx_seat_inventory_sweep (status, expires_at),

    -- 1인당 좌석 상한 조사용 (I-12 는 별도 카운터 행으로 강제한다. 이 인덱스는 조회 편의).
    KEY idx_seat_inventory_held_by (held_by, status),

    CONSTRAINT chk_seat_inventory_status
        CHECK (status IN ('AVAILABLE', 'HELD', 'PAYING', 'SOLD'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
