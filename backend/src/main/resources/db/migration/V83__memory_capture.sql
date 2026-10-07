-- 사람이 보낸 대화 turn 의 실행에 그 질문 메시지를 잇는다. memory_remember 가 바로 저장할지 판정할 때 읽는다(ADR-094).
-- 예약 작업, 먼저 살펴보기, 맡긴 일의 결과를 전하는 turn, 맡겨서 도는 실행은 줄이 없다.
-- 실행 기록과 메시지를 지우는 운영 경로는 없지만, 이 표가 그 표들의 정리를 막지 않게 FK 를 두지 않는다. 읽는 쪽이 메시지가 없으면 없는 줄로 본다.
CREATE TABLE execution_question (
    execution_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (execution_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 에이전트가 memory_remember 로 남긴 기록 한 줄이다. 대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고 되돌리기에 쓴다.
-- memory 줄은 되돌리거나 사람이 지우면 없어지므로 FK 를 두지 않는다. 사용자와 대화도 같은 까닭으로 잇지 않는다. 화면은 줄이 없는 기록을 그리지 않는다.
CREATE TABLE memory_capture (
    id BIGINT NOT NULL AUTO_INCREMENT,
    memory_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    execution_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    base_revision INT NULL,
    created_at DATETIME(6) NOT NULL,
    undone_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_memory_capture_conversation ON memory_capture (conversation_id, id);
CREATE INDEX idx_memory_capture_execution ON memory_capture (execution_id);
