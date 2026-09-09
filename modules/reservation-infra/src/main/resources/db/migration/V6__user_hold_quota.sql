-- ---------------------------------------------------------------------------
-- user_hold_quota — I-12(1인당 4석 상한)의 카운터 행.
--
-- ★ 왜 집계가 아니라 카운터 행인가
--
--   SELECT COUNT(*) FROM seat_inventory WHERE held_by = ? 로 검증하면
--   다른 트랜잭션의 미커밋 홀드가 보이지 않는다. 같은 사용자가 3석 요청 2개를 동시에
--   보내면 둘 다 "내 것 0석" 만 보고 통과해 6석을 점유한다 (CLAUDE.md 규칙 8).
--   TASK-002G-user-hold-quota-race.md 가 그것을 결정적으로 재현했다.
--
--   카운터 행은 검사와 증가를 한 문장으로 만든다. 같은 사용자의 요청끼리만 그 행에서
--   직렬화되므로 다른 사용자에게는 영향이 없다.
--
-- ★ 범위가 sale_event_id 인 이유
--
--   운행편(schedule_id)을 범위로 쓰면 운행편만 바꿔 상한을 우회할 수 있다.
--   TASK-002G-D §2 가 6석·8석으로 재현했다. 같은 판매 회차의 여러 운행편은
--   하나의 카운터로 합산한다.
--
--   FK 대상인 sale_event 는 V4 가 만들었다. 그전까지 "FK 대상이 없어 이 마이그레이션을
--   쓸 수 없다" 던 것이 그때 해소됐다.
--
-- ★ 이 테이블은 비어 있는 채로 시작한다 — backfill 하지 않는다
--
--   기존 활성 좌석이 있는 배포에서 카운터를 채우지 않는다. 이유는 하나다:
--   I-12 가 한 번도 강제된 적이 없으므로 상한을 넘는 사용자가 이미 있을 수 있고,
--   그 값은 아래 CHECK 를 통과하지 못한다. 어느 좌석을 빼야 하는지는 데이터가 답하지
--   않는다 — 정책 결정이 필요하다. 마이그레이션이 그것을 대신 정하면 안 된다.
--
--   비어 있어도 지금은 안전하다. 아직 아무도 이 표를 읽지 않기 때문이다.
--   위험은 Task 2H 가 요청 경로에 quota 검사를 배선하는 순간 생긴다.
--
-- ★ 스키마 배포와 quota 강제 활성화는 별개 절차다
--
--   이 마이그레이션이 하는 일은 "빈 표를 만드는 것" 뿐이다. 그것만으로는 아무것도
--   강제되지 않으므로 무중단으로 적용해도 된다.
--
--   강제 활성화는 다르다. 기존 활성 좌석이 있는 배포에서는 아래 전제가 모두 충족돼야 한다.
--   ★ 순서만으로는 부족하다 — backfill 과 검증 사이에 기존 쓰기 경로가 좌석을 바꾸면
--     검증 결과가 그 즉시 낡은 것이 된다. 그래서 (a)(b) 로 쓰기를 멈춘 뒤에 (c)(d) 를 한다.
--
--     (a) 관련 쓰기 경로를 중단한다 — 선점, 결제 상태 전이, 확정, 해제, 만료
--     (b) 모든 인스턴스의 배치·스위퍼·비동기 콜백까지 통제하고,
--         진행 중이던 관련 트랜잭션이 끝났는지 확인한다
--     (c) 이상 데이터를 점검한 뒤 운영자가 명시적으로 backfill 한다
--           - 상한 초과:  GROUP BY ... HAVING COUNT(*) > 4   → 행이 있으면 중단
--           - 소유자 누락: status IN ('HELD','PAYING') AND held_by IS NULL → 행이 있으면 중단
--     (d) 쓰기가 멈춘 상태에서 load-test/verify/user_hold_quota_drift.sql 의
--         세 결과(V-7a·V-7b·V-7c)가 모두 0행인지 확인한다
--     (e) 모든 쓰기 경로가 quota 증가·감소를 좌석 변경과 같은 트랜잭션으로 수행하는
--         버전인지 확인하고, 그렇지 않은 구버전 인스턴스의 쓰기를 차단한다
--     (f) 위가 모두 충족된 뒤에만 쓰기를 재개한다
--
--   ★ 쓰기를 재개하지 않는 조건
--     - (c) 또는 (d) 점검에서 한 행이라도 나온 경우
--     - 일부 인스턴스가 (e) 의 버전으로 전환되지 않은 경우
--     - 진행 중이던 트랜잭션의 종료를 확인하지 못한 경우
--   이 경우 quota 를 켜면 카운터가 실제와 어긋난 채 상한이 강제되어,
--   정상 요청이 거부되거나 상한이 무력해진다. 원인을 해소한 뒤 (c) 부터 다시 한다.
--
--   ★ 지금은 활성화할 수 없다. 요청 경로에 quota 를 부르는 배선이 없기 때문이다 (Task 2H).
--
--   자세한 절차와 중단 기준은 docs/experiments/TASK-002G-G-user-hold-quota.md §4.
--
-- 시각 컬럼은 다른 테이블과 같이 DATETIME(3) 이다.
-- ---------------------------------------------------------------------------

CREATE TABLE user_hold_quota
(
    -- quota 범위. I-12 는 판매 회차 단위다 (TASK-002G-D).
    sale_event_id BIGINT      NOT NULL,

    user_id       BIGINT      NOT NULL COMMENT '예매 사용자',

    -- 현재 활성 선점(HELD·PAYING) 좌석 수.
    -- SOLD 와 AVAILABLE 은 활성 선점이 아니므로 세지 않는다 (TASK-002G-D §4).
    held_seats    SMALLINT    NOT NULL DEFAULT 0,

    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    -- ★ PK 순서가 곧 잠금 순서다 (TASK-002G-B·2G-D §6).
    --   여러 quota 행을 잠그는 경로(만료 배치)는 이 키의 오름차순으로 접근한다.
    --   sale_event_id 가 선두이므로 아래 FK 가 요구하는 인덱스도 이 PK 가 충족한다 —
    --   보조 인덱스를 따로 만들지 않는다.
    PRIMARY KEY (sale_event_id, user_id),

    -- ★ 최후의 방어선. 조건부 UPDATE 가 실패해도 잘못된 값이 저장되지 않는다.
    --   상한 4 는 REQUIREMENTS.md P-2 다. 음수 방어는 이중 감소와 과대 계산을 막는다.
    CONSTRAINT ck_user_hold_quota_range
        CHECK (held_seats >= 0 AND held_seats <= 4),

    CONSTRAINT fk_user_hold_quota_sale_event
        FOREIGN KEY (sale_event_id) REFERENCES sale_event (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
