-- 에이전트 기본 모델을 없애고 세 컬럼을 지운다(ADR-20261101 / model-setting). 값은 그룹 설정으로 옮기지 않는다.
-- 관리자가 그룹 설정에서 다시 정한다. 되돌리기 어려운 삭제라 다른 이관과 섞지 않고 이 파일 하나로 둔다.
-- 여러 컬럼을 한 문장으로 지우는 문법이 MySQL 과 H2 의 MySQL 모드에서 달라 컬럼마다 따로 지운다.
ALTER TABLE agent DROP COLUMN default_model_provider;
ALTER TABLE agent DROP COLUMN default_model;
ALTER TABLE agent DROP COLUMN default_reasoning_effort;
