-- 대화에 올린 사진 한 장이 한 행이다. 본문은 공유 디렉터리의 파일로 두고 여기에는 그것을 가리키는 것만 둔다.
-- 근거는 ADR-020 에 있다.
--
-- 행을 지우지 않는다. 파일을 지우고 deleted_at 만 적는다.
-- 볼 수 있는지는 deleted_at 이 비어 있는지 하나로 정한다. expires_at 은 언제 지울지만 정한다.
--
-- message_id 가 비어 있으면 올렸지만 아직 메시지와 함께 보내지 않은 것이다.
--
-- stored_name 은 NULL 을 허용한다. 값이 {id}.{확장자} 인데 id 는 행을 넣은 뒤에야 생긴다.
-- 같은 트랜잭션에서 id 를 받은 직후 채우므로 커밋된 행에는 언제나 값이 있다.
CREATE TABLE chat_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NULL,
    uploaded_by_user_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_chat_attachment_conversation (conversation_id, id),
    KEY ix_chat_attachment_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
