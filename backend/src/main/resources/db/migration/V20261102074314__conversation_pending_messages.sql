-- 응답 중에 보낸 대기 메시지(chat_pending_message)를 대화 줄의 JSON 칸 하나로 옮긴다(ADR-20261102 / table-reduce-r2, ADR-048).
-- 이 파일은 칸만 더하고, 값은 다음 Java 마이그레이션이 옛 테이블에서 옮긴다.
-- 직전 이미지는 이 칸을 모르고 대화를 쓰므로 NULL 을 허용한다. 옛 테이블은 배포를 확인한 뒤 다음 PR 이 지운다.
ALTER TABLE conversation ADD COLUMN pending_messages LONGTEXT NULL COMMENT '응답 중에 보내 아직 다음 turn 으로 보내지 않은 메시지의 JSON. version, held(참이면 사용자가 풀 때까지 보내지 않음), items(id, content, createdAt)를 담는다. 평문이다. 비어 있으면 대기 메시지가 없다';
