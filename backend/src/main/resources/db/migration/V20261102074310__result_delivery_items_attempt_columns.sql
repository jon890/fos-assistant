-- 결과 전달 항목(result_delivery_item)과 시도(result_delivery_attempt)를 묶음 줄의 칸으로 옮긴다(ADR-20261102 / table-reduce-r2, ADR-075).
-- 항목은 JSON 칸 하나로, 시도는 마지막 시도 하나만 칸으로 둔다. 앞의 시도는 남기지 않는다.
-- 이 파일은 칸과 색인만 더하고, 값은 다음 Java 마이그레이션이 옛 테이블에서 옮긴다.
-- 직전 이미지는 이 칸을 모르고 묶음을 쓰므로 NULL 을 허용한다. 옛 테이블을 지우고 NOT NULL 로 바꾸는 일은 배포를 확인한 뒤 DROP PR 이 한다.
ALTER TABLE result_delivery ADD COLUMN items_json TEXT NULL COMMENT '묶음에 든 결과들의 JSON 문자열. 버전과 넣은 순서의 (source, resultKey) 목록이고 결과 본문은 없다. 옛 코드가 쓴 줄은 비어 있을 수 있다';
ALTER TABLE result_delivery ADD COLUMN last_attempt_status VARCHAR(20) NULL COMMENT '마지막 전달 시도의 상태. RUNNING 은 도는 중, SUCCEEDED 는 답을 남김, FAILED 는 실패, STOPPED 는 사용자 중지. 옛 코드가 쓴 줄은 비어 있을 수 있다';
ALTER TABLE result_delivery ADD COLUMN last_attempt_execution_id BIGINT NULL COMMENT '마지막 시도의 부모 turn 실행 줄(agent_execution.id). 외래 키가 없다. 실행 줄을 만들기 전에 끝났으면 NULL';
ALTER TABLE result_delivery ADD COLUMN last_attempt_notice_message_id BIGINT NULL COMMENT '마지막 시도가 저장한 마지막 SYSTEM 줄(chat_message.id). 외래 키가 없다. 화면이 이 줄 아래에 상태를 그린다';
ALTER TABLE result_delivery ADD COLUMN last_attempt_error_code VARCHAR(64) NULL COMMENT '마지막 시도가 FAILED 일 때의 오류 코드. 실행 줄 없이 내려갔으면 INTERRUPTED. 그 밖에는 NULL';
ALTER TABLE result_delivery ADD COLUMN last_attempt_started_at DATETIME(6) NULL COMMENT '마지막 시도를 시작한 UTC 시각. 옛 코드가 쓴 줄은 비어 있을 수 있다';
ALTER TABLE result_delivery ADD COLUMN last_attempt_finished_at DATETIME(6) NULL COMMENT '마지막 시도가 끝난 UTC 시각. RUNNING 이면 NULL';
CREATE INDEX idx_result_delivery_last_attempt_status ON result_delivery (last_attempt_status, last_attempt_started_at);
CREATE INDEX idx_result_delivery_last_attempt_execution ON result_delivery (last_attempt_execution_id);
