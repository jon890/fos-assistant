-- 사진 관찰 요청 테이블(media_observation_request)를 관찰 줄에 합친다(ADR-20261102 / media-observation-request-merge).
-- 이 파일은 칸과 유일 제약만 더하고, 값은 다음 파일이 옛 테이블에서 옮긴다.
-- 직전 이미지는 이 칸을 모르고 관찰을 쓰므로 NULL 을 허용한다. NULL 끼리는 유일 제약에 걸리지 않는다.
-- NOT NULL 로 바꾸고 옛 테이블과 analysis_key 를 지우는 일은 이 변경의 배포를 확인한 뒤 다음 PR 이 한다.
ALTER TABLE media_observation ADD COLUMN request_id CHAR(36) NULL COMMENT '이 revision 을 만든 저장 요청의 UUID. 같은 첨부 안에서 유일하고, 같은 UUID 재시도는 이 줄을 돌려준다. 옛 코드가 쓴 줄은 비어 있을 수 있다';
ALTER TABLE media_observation ADD COLUMN request_hash CHAR(64) NULL COMMENT '그 요청 내용의 SHA-256 16진수. 같은 UUID 재시도의 내용이 다르면 409 로 거절하는 데 쓴다. 옛 코드가 쓴 줄은 비어 있을 수 있다';
ALTER TABLE media_observation ADD CONSTRAINT uk_media_observation_request_id UNIQUE (attachment_id, request_id);
