-- 지운 에이전트를 정리할 때 승인 이력은 남기고 에이전트만 비운다(ADR-20261009 / agent-purge).
ALTER TABLE connector_action MODIFY COLUMN agent_id BIGINT NULL;
