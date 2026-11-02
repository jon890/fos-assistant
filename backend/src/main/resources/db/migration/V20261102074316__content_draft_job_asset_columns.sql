-- 원고 저장 job(content_draft_job)을 snapshot 줄의 칸으로, 사진 참조(content_draft_asset)를 revision 의 JSON 칸으로 옮긴다
-- (ADR-20261102 / table-reduce-r2 의 원고 D1). 이 파일은 칸과 유일 제약만 더하고, 값은 다음 Java 이관이 옛 테이블에서 옮긴다.
-- assets_json 은 H2 와 MySQL 의 JSON 형 바인딩 차이를 피하려고 문자열 칸으로 두고, 모양은 Control Plane 이 저장할 때 정한다.
-- 직전 이미지는 이 칸을 모르고 옛 테이블에 쓰므로 모두 NULL 을 허용한다. NULL 끼리는 유일 제약에 걸리지 않는다.
-- 옛 테이블을 지우고 필요한 칸을 NOT NULL 로 바꾸는 일은 이 변경의 배포를 확인한 뒤 DROP PR 이 한다.
ALTER TABLE content_draft_snapshot ADD COLUMN action_id BIGINT NULL COMMENT '이 snapshot 의 저장 요청이 만든 승인 줄(connector_action.id). NULL 이면 저장 요청 전이고 job 칸이 모두 비어 있다';
ALTER TABLE content_draft_snapshot ADD COLUMN job_id CHAR(36) NULL COMMENT '승인할 때 한 번 예약한 worker job 의 UUID. 승인 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN job_status VARCHAR(32) NULL COMMENT '저장 job 의 상태. PREVIEW_READY 승인 대기, DISPATCHED 시작 전송, SAVING 저장 중, SUCCEEDED 저장됨, FAILED 저장 클릭 전 실패, UNKNOWN 결과 모름, STALE 실행 없이 끝남. NULL 이면 저장 요청 전';
ALTER TABLE content_draft_snapshot ADD COLUMN job_created_at DATETIME(6) NULL COMMENT '저장 요청으로 job 을 만든 UTC 시각. 저장 요청 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN approved_by BIGINT NULL COMMENT '저장을 승인한 사용자 id. 승인 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN approved_at DATETIME(6) NULL COMMENT '저장을 승인한 UTC 시각. 승인 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN last_checked_at DATETIME(6) NULL COMMENT 'worker 의 job 상태를 마지막으로 반영한 UTC 시각. 확인한 적이 없으면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN start_claimed_at DATETIME(6) NULL COMMENT 'worker 에게 시작 허가를 한 번 준 UTC 시각. 허가 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN save_claimed_at DATETIME(6) NULL COMMENT 'worker 에게 저장 클릭 허가를 한 번 준 UTC 시각. 허가 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN job_stage VARCHAR(32) NULL COMMENT 'worker 가 알린 진행 단계의 고정 코드. 알린 적이 없으면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN save_clicked BOOLEAN NULL COMMENT 'worker 가 저장 단추를 눌렀다고 알렸는가. 저장 요청 전이면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN error_code VARCHAR(64) NULL COMMENT '저장 job 이 실패하거나 닫힌 고정 코드. 화면의 글은 담지 않는다. 없으면 NULL';
ALTER TABLE content_draft_snapshot ADD COLUMN error_reason VARCHAR(64) NULL COMMENT 'worker 가 알린 실패 사유의 고정 코드. error_code 와 같은 모양만 담는다. 없으면 NULL';
ALTER TABLE content_draft_snapshot ADD CONSTRAINT uk_content_snapshot_action UNIQUE (action_id);
ALTER TABLE content_draft_snapshot ADD CONSTRAINT uk_content_snapshot_job UNIQUE (job_id);
ALTER TABLE content_draft_revision ADD COLUMN assets_json LONGTEXT NULL COMMENT '본문의 사진 블록이 가리키는 첨부의 JSON 배열 문자열(암호문 아님). 항목은 position(블록 순서), attachmentId, sourceFingerprint(첨부 원본 SHA-256 16진수), observationId, observationRevision(관찰을 가리키지 않으면 둘 다 null). 사진이 없으면 []. NULL 이면 옛 코드가 쓴 줄이다';
