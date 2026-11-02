-- 테스트 DB 는 엔티티로 스키마를 만들어 엔티티가 없는 옛 허락 테이블이 생기지 않는다.
-- 사용자 정리와 정리 미리보기만 이 두 표를 native 질의로 지우고 세므로 그 질의가 도는 데 필요한 칸만 만든다(ADR-20261102 / table-reduce-r2).
-- 옛 테이블을 지우는 PR 이 이 파일과 application-test.yml 의 import_files 를 함께 지운다.
CREATE TABLE IF NOT EXISTS connector_tool_grant (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, connector_id VARCHAR(128) NOT NULL, tool_name VARCHAR(128) NOT NULL, expires_at TIMESTAMP(6) NULL, revoked_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL);
CREATE TABLE IF NOT EXISTS connector_conversation_grant (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL, agent_id BIGINT NOT NULL, connector_id VARCHAR(128) NOT NULL, tool_name VARCHAR(128) NOT NULL, action_id BIGINT NOT NULL, expires_at TIMESTAMP(6) NOT NULL, revoked_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL);
