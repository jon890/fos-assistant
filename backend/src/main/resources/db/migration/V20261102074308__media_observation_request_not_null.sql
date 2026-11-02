-- 관찰 줄의 요청 UUID 와 hash 를 NOT NULL 로 바꾸고 재사용 분석 key 칸을 지운다(ADR-20261102 / media-observation-request-merge).
-- 앞 파일이 직전 이미지가 쓴 줄을 다시 채웠다. 비어 있는 줄이 남았으면 이 문장이 실패해 배포가 멈춘다.
-- MODIFY COLUMN 은 COMMENT 를 새로 적어야 하므로 비어 있을 수 있다는 문구를 뺀 설명으로 다시 적는다.
-- 여러 컬럼을 한 문장으로 바꾸는 문법이 MySQL 과 H2 의 MySQL 모드에서 달라 컬럼마다 따로 바꾼다.
ALTER TABLE media_observation MODIFY COLUMN request_id CHAR(36) NOT NULL COMMENT '이 revision 을 만든 저장 요청의 UUID. 같은 첨부 안에서 유일하고, 같은 UUID 재시도는 이 줄을 돌려준다';
ALTER TABLE media_observation MODIFY COLUMN request_hash CHAR(64) NOT NULL COMMENT '그 요청 내용의 SHA-256 16진수. 같은 UUID 재시도의 내용이 다르면 409 로 거절하는 데 쓴다';
ALTER TABLE media_observation DROP COLUMN analysis_key;
