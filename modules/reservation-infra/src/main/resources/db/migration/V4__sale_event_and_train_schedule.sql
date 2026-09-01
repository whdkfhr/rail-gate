-- ---------------------------------------------------------------------------
-- sale_event / train_schedule — I-12 quota 범위의 실체.
--
-- I-12 의 범위는 판매 회차 단위다 (TASK-002G-D). 운행편을 범위로 쓰면 운행편만 바꿔
-- 상한을 우회할 수 있다는 것을 2G-D §2 가 6석·8석으로 재현했다.
-- 그 범위를 가리킬 테이블이 지금까지 없었다.
--
-- ★ 이 마이그레이션은 테이블만 만든다. seat_inventory 에 외래 키를 걸지 않는다.
--   기존 seat_inventory.schedule_id 는 참조 대상이 없는 값이고(V1 주석),
--   여기서 바로 FK 를 걸면 기존 행이 전부 orphan 이 되어 실패한다.
--   연결은 V5 가 하며, 그 사이에 운영자가 매핑을 입력하는 단계가 있다.
--   자세한 절차는 docs/experiments/TASK-002G-E-B2-sale-event-persistence.md §4.
--
-- ★ seat_inventory 에 sale_event_id 를 비정규화하지 않는다.
--   TASK-002G-E-B1 §8 의 결정이다. 실제 MySQL 8.4 에서 좌석 60,000행 기준
--   정규화 조인은 eq_ref/PRIMARY 였고, 읽는 행 수가 요청 좌석 수(P-2: 최대 4)에 갇혔다.
--   좌석을 400행 → 60,000행 으로 150배 늘려도 읽는 행 수가 늘지 않았다.
--   조인 비용이 0 이라는 뜻은 아니다 — 요청당 최대 6행을 더 읽는다.
--   그 비용이 전체 크기가 아니라 요청 크기에 매여 있어서 받아들인 것이다.
--
-- 시각 컬럼은 DATETIME(3) 이다. 오픈 판정이 NOW(3) 기준이므로 밀리초 정밀도가 없으면
-- 같은 초 안의 순서를 구분할 수 없다 (CLAUDE.md 규칙 7).
-- ---------------------------------------------------------------------------

CREATE TABLE sale_event
(
    id        BIGINT       NOT NULL COMMENT '판매 회차 식별자. quota 범위 키이기도 하다',

    name      VARCHAR(100) NOT NULL COMMENT '예: 2026년 추석 승차권 1차 판매',

    -- 오픈 판정 기준. 조건부 UPDATE 의 opens_at <= NOW(3) 이 이 값을 읽는다.
    opens_at  DATETIME(3)  NOT NULL,

    -- 마감 "예정" 시각이다. 마감 판정의 근거가 아니다.
    -- 매진이나 운영 중단으로 인한 조기 마감은 정상 운영 행위이므로,
    -- close 의 조건에 now >= closes_at 을 넣지 않는다 (TASK-002G-E-A §3).
    -- 무기한 판매면 NULL 이다.
    closes_at DATETIME(3)  NULL,

    -- CHARACTER SET ascii COLLATE ascii_bin 인 이유는 seat_inventory.status 와 같다.
    -- 테이블 기본 콜레이션 utf8mb4_0900_ai_ci 는 대소문자를 구분하지 않으므로(_ci),
    -- 그대로 두면 'open' 이 CHECK 를 통과하고 조건부 UPDATE 의 WHERE status='OPEN' 도
    -- 소문자 행에 매칭된다. 상태값은 열거형이므로 바이트 단위로 비교해야 한다.
    status    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
              COMMENT 'SCHEDULED | OPEN | CLOSED. 대소문자를 구분한다',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- 전이표는 SCHEDULED → OPEN → CLOSED 뿐이다 (TASK-002G-E-A §3).
    -- SCHEDULED → CLOSED 를 허용하지 않는 이유는 열린 적 없는 회차를 닫는다는 것의
    -- 업무적 의미가 정해지지 않았기 때문이다. 허용하면 CLOSED 가 정상 마감과 취소를
    -- 동시에 뜻하게 된다. 오픈 전 취소 정책은 미결정 후속 사항이다.
    CONSTRAINT chk_sale_event_status
        CHECK (status IN ('SCHEDULED', 'OPEN', 'CLOSED')),

    -- 마감 예정 시각이 있다면 오픈 시각보다 뒤여야 한다.
    -- 같은 순간이면 열자마자 닫히는 회차가 된다.
    CONSTRAINT chk_sale_event_sale_period
        CHECK (closes_at IS NULL OR closes_at > opens_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- train_schedule — 운행편은 정확히 하나의 판매 회차에 속한다.
--
-- ★ 이 FK 가 보장하는 것과 보장하지 않는 것을 구분해야 한다 (TASK-002G-E-B1 §9).
--
--   보장함   — "유효한 sale_event 를 참조한다".
--              존재하지 않는 회차로의 INSERT·UPDATE 는 거부된다.
--   보장 안 함 — "처음 참조한 그 회차를 계속 참조한다".
--              존재하는 다른 회차로의 UPDATE 는 그대로 통과한다. 다른 규칙이다.
--
-- 소속이 바뀌면 이미 쌓인 quota 의 의미가 달라진다 — 그 사용자가 어느 회차에서 몇 석을
-- 잡고 있었는지 되짚을 수 없다 (2G-D §4).
--
-- 그 재배정을 막는 층은 이렇다:
--   도메인   TrainSchedule 인스턴스에 소속 변경 API 가 없다        (2G-E-A)
--   저장소   JdbcTrainScheduleRepository 가 재배정 UPDATE 를 주지 않는다
--   DB       이 FK 는 "유효한 회차 참조" 만 보장한다. UPDATE 는 막지 않는다
--   트리거   BEFORE UPDATE 로 막을 수 있으나 아직 적용하지 않았다
--
-- 트리거를 넣지 않은 이유: 바이너리 로깅이 켜진 MySQL 에서 CREATE TRIGGER 는 SUPER 를
-- 요구하므로 애플리케이션 계정으로 도는 Flyway 로는 만들 수 없다 (2G-E-B1 §10 에서 실측).
-- 마이그레이션 계정 분리가 선행돼야 하며 그 결정은 이 Task 의 범위가 아니다.
--
-- 정리하면, 도메인과 저장소의 정상 애플리케이션 경로에서는 소속 재배정을 차단하지만,
-- DB 직접 SQL 수준의 sale_event_id 변경은 아직 차단하지 못한다. 알려진 위험이다.
--
-- 열차 번호·출발 시각·구간 같은 운행 정보는 의도적으로 넣지 않았다.
-- 이 Task 가 확정하는 것은 quota 범위를 결정하는 소속 관계뿐이고,
-- 쓰이지 않는 컬럼을 미리 만들면 아직 정하지 않은 것을 정한 것처럼 보이게 된다.
-- ---------------------------------------------------------------------------

CREATE TABLE train_schedule
(
    id            BIGINT      NOT NULL COMMENT '열차 운행편 식별자. seat_inventory.schedule_id 가 가리킨다',

    -- NOT NULL 이다. 소속 없는 운행편이 존재하면 그 좌석은 어느 quota 카운터에도
    -- 귀속되지 않아 I-12 의 상한 밖에 놓인다.
    sale_event_id BIGINT      NOT NULL,

    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- "이 회차에 속한 운행편" 조회용. FK 자체도 인덱스를 요구한다.
    KEY idx_train_schedule_sale_event (sale_event_id),

    CONSTRAINT fk_train_schedule_sale_event
        FOREIGN KEY (sale_event_id) REFERENCES sale_event (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
