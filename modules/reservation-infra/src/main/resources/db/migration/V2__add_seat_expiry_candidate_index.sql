-- ---------------------------------------------------------------------------
-- 만료 후보 조회 전용 인덱스 (I-10).
--
-- 기존 idx_seat_inventory_sweep (status, expires_at) 로는
-- "오래 만료된 좌석부터" 를 얻을 수 없다. 첫 컬럼이 status 이므로
-- status IN ('HELD','PAYING') 이 두 개의 범위 스캔이 되고,
-- 전역 ORDER BY expires_at 은 인덱스로 해결되지 않아 filesort 가 된다.
--
-- 그 결과 LIMIT 이 조기 종료하지 못하고 만료 후보 전부를 읽어 정렬한 뒤 잘라낸다.
-- backlog 가 커질수록 비용이 선형으로 증가하며, 배치 크기가 유한한 이상
-- 특정 상태의 만료 행이 쌓이면 다른 상태의 더 오래된 좌석이 반복적으로 밀려난다.
--
-- MySQL 8.4, 20,000행(만료 후보 6,000행) 기준 실측:
--
--   (status, expires_at) + ORDER BY expires_at, id
--     Using filesort, 실제 스캔 6,000행, actual time 6.14ms
--
--   (expires_at, id) + ORDER BY expires_at, id
--     filesort 없음,  실제 스캔   100행, actual time 0.254ms
--
-- expires_at 이 NULL 인 AVAILABLE/SOLD 는 `expires_at <= NOW(3)` 범위에서
-- 자연히 제외되므로, status 필터를 인덱스 앞에 둘 이유가 없다.
-- 그래서 status 를 인덱스에 넣지 않고 범위 조건만으로 좁힌다.
--
-- 기존 인덱스는 삭제하지 않는다. 삭제 여부는 다른 경로의 영향까지 측정한 뒤 판단한다.
-- ---------------------------------------------------------------------------

ALTER TABLE seat_inventory
    ADD KEY idx_seat_inventory_expiry_candidate (expires_at, id);
