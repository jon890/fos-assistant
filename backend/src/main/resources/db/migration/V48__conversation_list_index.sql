-- 대화 목록은 사용자의 지우지 않은 대화를 `updated_at desc, id desc` 로 쪽마다 읽는다.
-- 지운 줄 조건(deleted_at)을 정렬 열 앞에 두어, 지운 대화가 쌓여도 읽는 줄 수가 한 쪽에 머문다.
CREATE INDEX ix_conversation_user_listing ON conversation (user_id, deleted_at, updated_at, id);
