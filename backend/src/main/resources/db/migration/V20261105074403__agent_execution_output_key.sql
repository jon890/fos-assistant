-- 위임 답 본문을 암호화한 데이터 key(ADR-20261008 / data-encryption). 비어 있으면 output_text 는 평문이다.
-- 외래 키를 두지 않는다. 데이터 key 를 지워 본문을 읽지 못하게 하는 길을 막지 않는다.
ALTER TABLE agent_execution MODIFY COLUMN output_text MEDIUMTEXT NULL COMMENT '실행이 생성한 결과 본문. output_key_id 가 있으면 암호문이고, 아직 결과가 없거나 대화 purge 로 지웠으면 NULL';
ALTER TABLE agent_execution ADD COLUMN output_key_id BIGINT NULL COMMENT '결과 본문 암호화에 쓴 사용자 데이터 key 번호. NULL 이면 평문이거나 결과가 없음';
