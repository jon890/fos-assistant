-- 대화 제목과 예약 작업의 제목·지시문을 암호화한 데이터 key(ADR-20261008 / data-encryption). 옆 칸이 비어 있으면 그 칸은 평문이다.
-- 외래 키를 두지 않는다. 데이터 key 를 지워 본문을 읽지 못하게 하는 길을 막지 않는다.
-- 암호문은 평문 UTF-8 바이트의 약 4/3 에 44자쯤이 붙으므로 칸 길이를 늘린다. 평문 글자 수 상한(대화 200, 작업 100)은 애플리케이션이 지킨다.
ALTER TABLE conversation MODIFY COLUMN title VARCHAR(1024) NOT NULL;
ALTER TABLE conversation ADD COLUMN title_key_id BIGINT NULL COMMENT '제목 암호화에 쓴 사용자 데이터 key 번호. NULL 이면 title 은 평문이거나 빈 제목';
ALTER TABLE task MODIFY COLUMN title VARCHAR(512) NOT NULL;
ALTER TABLE task ADD COLUMN title_key_id BIGINT NULL COMMENT '제목 암호화에 쓴 사용자 데이터 key 번호. NULL 이면 title 은 평문';
ALTER TABLE task ADD COLUMN instruction_key_id BIGINT NULL COMMENT '지시문 암호화에 쓴 사용자 데이터 key 번호. NULL 이면 instruction 은 평문이거나 점검 작업이라 비어 있음';
