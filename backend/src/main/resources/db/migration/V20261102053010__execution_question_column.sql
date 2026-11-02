-- 사람이 보낸 turn 의 질문 메시지를 실행 줄에 둔다(ADR-20261102 / table-reduce-r2).
-- 이 파일은 칸만 더하고, 값은 다음 파일이 execution_question 에서 옮긴다.
-- 직전 이미지는 이 칸을 모르고 실행을 쓰므로 NULL 을 허용한다.
-- 옛 execution_question 은 남긴다. 이 변경의 배포를 확인한 뒤 다음 PR 이 지운다.
ALTER TABLE agent_execution ADD COLUMN question_message_id BIGINT NULL COMMENT '사람이 보낸 turn 의 질문 메시지 내부 식별자. 새 질문과 다시 생성의 실행에만 있고 그 외 실행은 비어 있다. 대화를 정리하면 비운다. 외래 키는 없다';
