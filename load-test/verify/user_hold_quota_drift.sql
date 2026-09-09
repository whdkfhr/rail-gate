-- ---------------------------------------------------------------------------
-- V-7: user_hold_quota drift 탐지 (I-12)
--
-- 카운터와 실제 활성 좌석 수를 대조한다. **하나라도 행을 반환하면 CI 실패** —
-- INVARIANTS.md 의 다른 검증 쿼리와 같은 규약이다.
--
-- ★ 읽기 전용이다. 보정 SQL 을 함께 두지 않는다.
--
--   좌석과 카운터 중 무엇이 진실의 원천인지 근거 없이 정할 수 없다.
--   drift 의 원인에 따라 답이 다르고, 특히 확정(confirm) 경로의 quota 감소가 아직
--   구현되지 않아(TASK-002G-C 미해결) **카운터가 "옳게" 클 수 있다.**
--   그 상태에서 좌석 집계에 맞추면 진짜 문제를 지운다.
--   자동 보정을 기각한 근거는 TASK-002G-F §10 에 있다.
--
-- ★ 활성 선점의 정의
--
--   status IN ('HELD','PAYING') 뿐이다. SOLD 와 AVAILABLE 은 활성 선점이 아니다
--   (TASK-002G-D §4). 회차는 seat_inventory → train_schedule 조인으로 얻는다 —
--   좌석 행에 sale_event_id 를 복제하지 않기로 했다 (TASK-002G-E-B1 §8).
--
-- ★ 정상 상태를 보고하지 않는다
--
--   카운터 0 이고 활성 좌석도 0 인 사용자는 drift 가 아니다. 좌석을 다 놓아준 뒤의
--   정상 상태이며, 행을 지우지 않고 0 으로 남기는 것이 이 설계다.
--   아래 쿼리는 두 값이 다를 때만 반환한다.
--
-- MySQL 에 FULL OUTER JOIN 이 없어 두 방향을 각각 본다. 한쪽만 보면
-- "카운터 없이 좌석만 있는" 경우를 놓치는데, 그것이 상한 검사를 완전히 무력화하는
-- 가장 위험한 방향이다.
-- ---------------------------------------------------------------------------

-- V-7a: 카운터와 활성 좌석 수가 어긋난 사용자
--       (카운터 과대 / 과소 / 활성 좌석 없는 카운터를 모두 포함한다)
SELECT q.sale_event_id,
       q.user_id,
       q.held_seats                  AS quota_held_seats,
       COALESCE(a.active_seats, 0)   AS active_seats,
       CASE
           WHEN COALESCE(a.active_seats, 0) = 0        THEN 'QUOTA_WITHOUT_SEATS'
           WHEN q.held_seats > a.active_seats          THEN 'QUOTA_GREATER'
           ELSE                                             'QUOTA_LESS'
       END                           AS drift_kind
  FROM user_hold_quota q
  LEFT JOIN (
        SELECT ts.sale_event_id, s.held_by AS user_id, COUNT(*) AS active_seats
          FROM seat_inventory s
          STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
         WHERE s.status IN ('HELD', 'PAYING')
           AND s.held_by IS NOT NULL
         GROUP BY ts.sale_event_id, s.held_by
       ) a ON a.sale_event_id = q.sale_event_id AND a.user_id = q.user_id
 WHERE q.held_seats <> COALESCE(a.active_seats, 0)
 ORDER BY q.sale_event_id, q.user_id;

-- V-7b: 활성 좌석은 있는데 카운터 행이 없는 사용자
--       ★ 가장 위험한 방향이다. 그 사용자에게는 상한 검사가 아예 적용되지 않는다.
SELECT a.sale_event_id,
       a.user_id,
       NULL          AS quota_held_seats,
       a.active_seats,
       'SEATS_WITHOUT_QUOTA' AS drift_kind
  FROM (
        SELECT ts.sale_event_id, s.held_by AS user_id, COUNT(*) AS active_seats
          FROM seat_inventory s
          STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
         WHERE s.status IN ('HELD', 'PAYING')
           AND s.held_by IS NOT NULL
         GROUP BY ts.sale_event_id, s.held_by
       ) a
  LEFT JOIN user_hold_quota q
         ON q.sale_event_id = a.sale_event_id AND q.user_id = a.user_id
 WHERE q.user_id IS NULL
 ORDER BY a.sale_event_id, a.user_id;

-- V-7c: 소유자를 알 수 없는 활성 좌석 (held_by NULL)
--       ★ 위 두 쿼리로는 보이지 않는다. 그 좌석이 어느 집계에도 들어가지 않아
--         카운터와 집계가 "맞아 보인다". 따로 세야 한다.
--       만료 스위퍼도 이런 좌석은 소유자를 몰라 회수하지 못한다 (TASK-002G-F §9).
SELECT s.id AS seat_id,
       s.schedule_id,
       s.status,
       'ACTIVE_SEAT_WITHOUT_HOLDER' AS drift_kind
  FROM seat_inventory s
 WHERE s.status IN ('HELD', 'PAYING')
   AND s.held_by IS NULL
 ORDER BY s.id;
