-- 지운 대화의 본문을 실제로 지운 시각이다(ADR-20261008 / conversation-purge).
-- 사용자가 지우면 deleted_at 이 먼저 적히고, 정리 작업이 메시지와 첨부와 결과물, 실행의 본문, Hermes session 을 지운 뒤 이 칸을 적는다.
-- 대화 줄과 실행 줄은 본문 없이 남는다. 사용량 합계와 다른 표의 참조가 그 줄을 가리킨다.
ALTER TABLE conversation ADD COLUMN purged_at DATETIME(6) NULL;

CREATE INDEX ix_conversation_purge ON conversation (purged_at, deleted_at);
