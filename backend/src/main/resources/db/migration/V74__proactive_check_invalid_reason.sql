-- 결과 블록을 읽지 못한 살펴보기(outcome = INVALID_RESULT)가 어느 단계에서 떨어졌는지 남기는 칸을 더한다.
-- 칸의 뜻은 docs/backend/schema/proactive.md 가 갖는다. 모델 글은 담지 않는다.
-- 이미 있는 줄은 까닭을 모르므로 비워 둔다.

ALTER TABLE proactive_check ADD COLUMN invalid_reason VARCHAR(32) NULL;
