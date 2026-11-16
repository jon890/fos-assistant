-- 사람이 「직접 할게요」 로 브라우저를 쥔 시각이다. 쥔 동안 중계가 에이전트의 요청을 거절한다.
-- 뜻은 backend/docs/data-schema.md 의 「user_browser」 가, 결정은 docs/adr/ADR-20261116-browser-lock.md 가 갖는다. 새 테이블을 만들지 않는다.
ALTER TABLE user_browser ADD COLUMN human_held_at DATETIME(6) NULL COMMENT '사람이 브라우저를 쥔 시각(UTC). 쥐지 않았으면 NULL';
