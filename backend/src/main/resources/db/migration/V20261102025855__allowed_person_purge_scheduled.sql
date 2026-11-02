-- 사용자 정리 예약(plan320 phase 01). 새 표를 만들지 않고 허용 목록 줄에 칸 하나를 더한다.
ALTER TABLE allowed_person ADD COLUMN purge_scheduled_at DATETIME(6) NULL COMMENT '정리를 예약한 시각. 비어 있으면 예약이 없다. 이 시각에서 30일이 지나면 정리 작업이 사용자 데이터를 지운다';
