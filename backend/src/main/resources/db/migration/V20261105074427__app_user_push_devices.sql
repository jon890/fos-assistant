-- 웹 푸시 기기(브라우저 구독) 목록을 app_user 의 JSON 컬럼으로 둔다. 새 테이블을 만들지 않는다(ADR-20261120 / web-push).
-- 컬럼의 뜻과 쓰는 규칙은 backend/docs/data-schema.md 의 「app_user」 가 갖는다.
ALTER TABLE app_user ADD COLUMN push_devices TEXT NULL COMMENT '웹 푸시 기기 목록 JSON(version 1). 기기마다 푸시 서비스 주소와 그 SHA-256 hex, 브라우저 암호화 공개 키, 인증 값, 이름, 시간대, 등록 시각. NULL 이면 기기가 없다. 주소와 인증 값은 운영 조회 계정에 주지 않는다';
