-- ---------------------------------------------------------------------------
-- seat_inventory.schedule_id → train_schedule.id 외래 키.
--
-- V1 은 "train_schedule 테이블은 후속 Task 에서 추가하므로 지금은 외래 키를 걸지 않는다"
-- 고 적었다. 그 후속이 여기다.
--
-- ★ 이 마이그레이션은 Flyway 가 V4 다음에 자동으로 이어서 실행한다.
--   빈 스키마에서는 그래도 된다 — 좌석이 없으니 orphan 도 없다.
--   문제는 기존 seat_inventory 데이터가 있는 배포다. 그때는 아래 검증이 실패한다.
--
--   [A] 빈 스키마 (신규 설치·테스트)
--
--       flyway migrate            -- V1 → … → V5 까지 한 번에 이어진다
--
--   [B] 기존 seat_inventory 데이터가 있는 운영 배포
--
--       flyway migrate -target=4  -- ★ V4 에서 의도적으로 멈춘다
--       -- 운영자가 근거 있는 sale_event / train_schedule 매핑을 입력한다 (수동)
--       -- orphan 이 0 인지 확인한다 (아래 탐지 쿼리와 같은 것)
--       flyway migrate            -- V5 적용
--
--       target=4 를 빼먹고 그냥 migrate 하면 V5 가 실패하고 이력에 실패가 남는다.
--       그 경우 원인(미매핑 운행편)을 없앤 뒤 flyway repair 로 이력을 정리하고
--       다시 migrate 해야 한다. 원인만 없애고 migrate 하면 Flyway 가 검증에서 거부한다.
--
--   매핑 입력을 마이그레이션 파일로 만들지 않은 이유:
--   backfill 은 "이 운행편이 어느 판매 회차에 속하는가" 를 적는 일인데,
--   이 저장소에는 그 답의 원천이 없다. 운영 데이터도, 매핑 표도, 등록 API 도 없다.
--   마이그레이션이 그 답을 갖고 있으려면 지어내는 수밖에 없고, 그것은
--   TASK-002G-E-B1 §12 가 재현해 금지한 세 지름길과 정확히 같다:
--     - 미매핑 운행편을 기본 회차에 몰아넣기 → 무관한 회차의 quota 에 합산된다
--     - schedule_id 를 sale_event_id 로 대입   → 2G-D §2 가 기각한 운행편 단위 범위가 된다
--     - orphan 좌석 삭제                      → 재고가 조용히 사라진다
--   세 가지 모두 FK 를 만족시켜 마이그레이션을 초록색으로 만든다. 그래서 위험하다.
--
-- ★ 실패 정책: orphan 이 한 건이라도 있으면 이 마이그레이션은 실패한다.
--   FK 추가만으로도 실패하지만, 그 오류 메시지는 외래 키 위반이라고만 말한다.
--   아래 가드는 실패 원인이 "매핑되지 않은 운행편" 임을 제약 이름으로 드러낸다.
--   실패해도 좌석 행은 그대로이고 FK 도 붙지 않는다.
-- ---------------------------------------------------------------------------

-- 매핑되지 않은 운행편 수를 센다. 0 이 아니면 CHECK 가 위반되어 마이그레이션이 멈춘다.
--
-- 세션 임시 테이블을 쓰는 이유: 실패해도 스키마에 흔적이 남지 않는다. 커넥션이 닫히면 사라진다.
-- DROP IF EXISTS 를 먼저 두는 이유: Flyway 가 재시도할 때 커넥션 풀에서 같은 커넥션을 받으면
-- 앞선 실패의 임시 테이블이 아직 그 세션에 살아 있다. 그대로 CREATE 하면
-- "Table 'migration_guard_v5' already exists" 로 엉뚱한 곳에서 멈춘다.
DROP TEMPORARY TABLE IF EXISTS migration_guard_v5;

CREATE TEMPORARY TABLE migration_guard_v5
(
    unmapped_train_schedule_count INT NOT NULL,
    CONSTRAINT chk_no_unmapped_train_schedule
        CHECK (unmapped_train_schedule_count = 0)
) ENGINE = InnoDB;

INSERT INTO migration_guard_v5 (unmapped_train_schedule_count)
SELECT COUNT(DISTINCT s.schedule_id)
  FROM seat_inventory s
  LEFT JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE ts.id IS NULL;

DROP TEMPORARY TABLE migration_guard_v5;

-- ---------------------------------------------------------------------------
-- 여기까지 왔다면 모든 좌석의 schedule_id 가 등록된 운행편을 가리킨다.
--
-- 이 FK 가 이후로 보장하는 것:
--   - 매핑되지 않은 운행편의 좌석은 INSERT 되지 않는다. 새 orphan 이 유입되지 않는다.
--   - 좌석이 붙은 운행편은 DELETE 되지 않는다.
--     TASK-002G-E-B1 §11 이 트리거의 구멍으로 지목한 "DELETE 후 재삽입" 을 좁힌다.
--
-- 보장하지 않는 것:
--   - train_schedule.sale_event_id 의 UPDATE. 그것은 트리거나 컬럼 권한 회수의 몫이며
--     둘 다 마이그레이션 계정 분리를 전제로 한다 (V4 주석 참고).
--
-- 인덱스를 따로 만들지 않는다. V1 의 uk_seat_inventory_seat (schedule_id, seat_no) 가
-- schedule_id 를 선두 컬럼으로 갖고 있어 FK 요건을 충족한다.
-- ---------------------------------------------------------------------------

ALTER TABLE seat_inventory
    ADD CONSTRAINT fk_seat_inventory_schedule
        FOREIGN KEY (schedule_id) REFERENCES train_schedule (id);
