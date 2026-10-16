-- 발급된 토큰은 모두 취소됐고 실행 밖 문서 읽기 기능을 폐기한다(ADR-056).
-- 토큰을 참조하는 권한 표부터 제거한다. 기억과 에이전트 토큰은 보존한다.
DROP TABLE service_token_collection;
DROP TABLE service_token;
