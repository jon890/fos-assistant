-- 결과 전달 묶음의 항목, 마지막 시도 상태와 시작 시각을 NOT NULL 로 바꾼다(ADR-20261102 / table-reduce-r2 의 결과 전달 D3).
-- 앞 파일(V20261102074328)이 직전 이미지가 옛 표에만 쓴 항목과 시도를 다시 채웠다. 비어 있는 줄이 남았으면 이 문장이 실패해 옛 표를 지우지 않고 배포가 멈춘다.
-- 실행 줄, 알림 줄, 오류 코드, 끝 시각은 시도가 도는 동안이나 실행 줄 없이 끝난 경우 비므로 NULL 을 허용한 채 둔다.
-- MODIFY COLUMN 은 COMMENT 를 새로 적어야 하므로 옛 코드가 쓴 줄은 비어 있을 수 있다는 문구를 뺀 설명으로 다시 적는다.
ALTER TABLE result_delivery MODIFY COLUMN items_json TEXT NOT NULL COMMENT '묶음에 든 결과들의 JSON 문자열. 버전과 넣은 순서의 (source, resultKey) 목록이고 결과 본문은 없다';
ALTER TABLE result_delivery MODIFY COLUMN last_attempt_status VARCHAR(20) NOT NULL COMMENT '마지막 전달 시도의 상태. RUNNING 은 도는 중, SUCCEEDED 는 답을 남김, FAILED 는 실패, STOPPED 는 사용자 중지';
ALTER TABLE result_delivery MODIFY COLUMN last_attempt_started_at DATETIME(6) NOT NULL COMMENT '마지막 시도를 시작한 UTC 시각';
