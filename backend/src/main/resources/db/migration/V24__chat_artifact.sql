-- 에이전트가 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나가 한 행이다.
-- 본문은 공유 디렉터리의 파일로 두고 여기에는 그 파일을 가리키는 것만 둔다. 근거는 ADR-027 에 있다.
--
-- 행을 지우지 않는다. 보관 기간이 지나 파일을 지우면 deleted_at 만 적는다.
-- 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다. 그래서 유일 제약은 (message_id, path) 다.
CREATE TABLE chat_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    path VARCHAR(500) NOT NULL,
    byte_size BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_artifact_message_path (message_id, path),
    KEY ix_chat_artifact_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
