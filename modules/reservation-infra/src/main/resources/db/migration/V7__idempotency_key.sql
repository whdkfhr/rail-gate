-- ---------------------------------------------------------------------------
-- idempotency_key — 상태를 바꾸는 요청의 멱등키 (FR-2.9, I-18, ADR-009 후보 B).
--
-- ★ 지금은 선점 요청(HOLD_SEATS) 하나만 쓴다. 결과 열도 선점 결과의 모양이다.
--   해제·확정·결제 시작에 같은 계약을 적용할지는 정하지 않았다 (ADR-009 §6).
--
-- ★ 이 테이블만으로는 아무것도 강제되지 않는다
--   요청 경로에 TX0/TX1/TX2 조정자가 배선되지 않았다. 빈 표를 만들 뿐이므로 무중단으로 적용해도 된다.
--
-- ★ 소유권 = (generation, fence)
--   generation 은 INSERT 마다 새로 정하는 UUID 다. 인수는 같은 세대에서 fence 만 1 올린다.
--   INSERT 가 언제나 fence 1 로 시작하므로, 보존 기간 뒤 삭제·재생성된 행은 fence 만으로는
--   과거 행과 구별되지 않는다 (TASK-002I-A §4-1). 모든 소유권 조건부 UPDATE 가 두 열을 함께 본다.
--
-- ★ 키 비교는 바이트 단위다 — 대소문자 구분. 그래서 키·연산·fingerprint·세대 열은 ascii_bin 이다.
--
-- ★ 시각 열은 DATETIME(3) 이고 값은 DB 의 NOW(3) 로만 쓴다 (규칙 7).
--
-- ★ 보존 기간 삭제 작업은 없다. 삭제는 COMPLETED 이고 completed_at 이 보존 기간을 지난 행만
--   대상으로 해야 한다 — IN_PROGRESS 행을 지우면 실행 중인 시도의 결과가 남지 않는다.
--   그 정리 계약은 후속 작업이며, 필요해지면 그때 (state, completed_at) 인덱스를 측정 후 추가한다.
--
-- ★ 외래 키를 두지 않는다
--   user_id 를 담을 사용자 테이블이 없다. sale_event_id·hold_id 는 "최초 결과" 의 기록이지
--   현재 상태가 아니다 — 회차나 홀드가 나중에 바뀌어도 재생할 값은 그대로여야 한다.
-- ---------------------------------------------------------------------------

CREATE TABLE idempotency_key
(
    -- 키 범위 (인증된 사용자, 연산, 클라이언트 키). 순서가 곧 PK 순서다.
    user_id             BIGINT       NOT NULL,
    operation           VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key     VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    -- 최초 요청의 SHA-256 16진수. 불일치 판정은 호출자가 한다.
    request_fingerprint CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    state               VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation          CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fence               BIGINT       NOT NULL,

    -- IN_PROGRESS 에서만 값이 있다. 리스 만료 판정은 lease_until <= NOW(3).
    lease_until         DATETIME(3)  NULL,

    -- COMPLETED 에서만 값이 있다. SUCCEEDED 면 선점 결과 세 열이 모두 있고, 업무 실패면 모두 없다.
    result_type         VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    hold_id             CHAR(36)     NULL,
    sale_event_id       BIGINT       NULL,
    seat_count          SMALLINT     NULL,

    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at        DATETIME(3)  NULL,

    PRIMARY KEY (user_id, operation, idempotency_key),

    CONSTRAINT ck_idempotency_key_state
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),

    CONSTRAINT ck_idempotency_key_fence
        CHECK (fence >= 1),

    CONSTRAINT ck_idempotency_key_result_type
        CHECK (result_type IS NULL
            OR result_type IN ('SUCCEEDED', 'SEAT_UNAVAILABLE', 'QUOTA_EXCEEDED', 'SALE_EVENT_SCOPE_REJECTED')),

    -- ★ 상태와 결과 필드의 일관성. 저장소를 거치지 않은 쓰기도 어긋난 행을 만들 수 없게 하는 최후의 방어선.
    CONSTRAINT ck_idempotency_key_shape
        CHECK (
            (state = 'IN_PROGRESS'
                AND lease_until IS NOT NULL
                AND result_type IS NULL AND hold_id IS NULL AND sale_event_id IS NULL AND seat_count IS NULL
                AND completed_at IS NULL)
            OR
            (state = 'COMPLETED'
                AND lease_until IS NULL
                AND completed_at IS NOT NULL
                AND (
                    (result_type = 'SUCCEEDED'
                        AND hold_id IS NOT NULL AND sale_event_id IS NOT NULL AND seat_count >= 1)
                    OR
                    (result_type IN ('SEAT_UNAVAILABLE', 'QUOTA_EXCEEDED', 'SALE_EVENT_SCOPE_REJECTED')
                        AND hold_id IS NULL AND sale_event_id IS NULL AND seat_count IS NULL)
                ))
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
