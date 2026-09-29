-- 대화의 뿌리 session 과 실행 줄의 session 을 적는다(ADR-031).
-- MCP agent_* 호출은 서명한 뿌리 session 을 들고 오고, 서버는 그 session 으로 도는 실행을 찾아 부모로 쓴다.
-- 새 대화는 첫 turn 전에 Control Plane 이 정한 session 을 hermes_session_id 와 hermes_root_session_id 에 함께 적는다.
-- 압축 교체로 Hermes 가 다른 session 을 돌려주면 hermes_session_id 만 바뀌고 뿌리 칸은 그대로다.
-- 이 칸이 생기기 전의 대화와 실행은 비어 있다.
-- delegation_key 와 output_text 는 다른 에이전트에게 맡긴 실행만 채운다.
-- 여러 칸을 한 문장으로 더하는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 칸마다 따로 더한다.

ALTER TABLE conversation ADD COLUMN hermes_root_session_id VARCHAR(128) NULL;

ALTER TABLE agent_execution ADD COLUMN hermes_session_id VARCHAR(128) NULL;
ALTER TABLE agent_execution ADD COLUMN delegation_key VARCHAR(64) NULL;
ALTER TABLE agent_execution ADD COLUMN output_text MEDIUMTEXT NULL;

CREATE UNIQUE INDEX uk_agent_execution_delegation_key ON agent_execution (delegation_key);
CREATE INDEX idx_agent_execution_session_status ON agent_execution (hermes_session_id, status);
