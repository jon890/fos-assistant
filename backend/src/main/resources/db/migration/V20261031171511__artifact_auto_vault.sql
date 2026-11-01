-- turn 이 끝날 때 완성 결과물을 요청자의 보관함에 자동으로 보관한다(#530 PR 3, 새 표를 늘리지 않는다).
-- 작업 기록은 결과물 행(chat_artifact 의 vault_user_id 가 빈 행) 자신의 칸이다. 재시작 뒤에는 PENDING 과 RUNNING 인 행을 다시 돈다.
-- 지우기 이력은 보관 사본 뿌리 행을 지우지 않고 본문만 비운 행이다. 그 행의 deleted_at 이 사용자가 지운 시각이다.
ALTER TABLE chat_artifact ADD COLUMN auto_vault_state VARCHAR(16) NULL COMMENT '결과물 행의 자동 보관 상태(PENDING, RUNNING, DONE, SKIPPED, FAILED). 비어 있으면 자동 보관 대상이 아니다. 보관 사본 행은 비어 있다';
ALTER TABLE chat_artifact ADD COLUMN auto_vault_error VARCHAR(64) NULL COMMENT '자동 보관이 FAILED 로 끝난 까닭의 오류 코드. 다시 보관하면 비운다';
ALTER TABLE chat_artifact ADD COLUMN auto_vault_attempts INT NOT NULL DEFAULT 0 COMMENT '자동 보관을 시도한 횟수. 일시 장애로 다시 시도할 때 상한을 센다';
CREATE INDEX ix_chat_artifact_auto_vault ON chat_artifact (auto_vault_state, id);
ALTER TABLE app_user ADD COLUMN artifact_auto_vault BOOLEAN NULL DEFAULT TRUE COMMENT '결과물 자동 보관을 켰는지. 기본은 켜짐이고 있는 사용자도 켜짐으로 채운다. 비어 있으면 코드의 기본값을 따른다';
