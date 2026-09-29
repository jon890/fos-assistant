-- 에이전트는 모델을 갖지 않는다. 모델은 대화가 고르고 막힌 계정은 Hermes 가 다룬다(ADR-030).
-- 에이전트의 모델 목록과 막힌 provider 기억, 에이전트의 모델 칸을 지운다. 두 표에는 외래 키가 없다.
-- 여러 칸을 한 문장으로 지우는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 칸마다 따로 지운다.
-- 이 버전을 배포한 뒤 이전 이미지로 되돌리려면 DB 도 배포 전 백업으로 되돌려야 한다.
-- 이전 이미지는 agent.provider 칸을 찾아 Schema validation 에서 실패한다.

DROP TABLE agent_model_option;
DROP TABLE provider_state;

ALTER TABLE agent DROP COLUMN provider;
ALTER TABLE agent DROP COLUMN model;
ALTER TABLE agent DROP COLUMN model_synced_at;
