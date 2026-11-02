-- 대화가 직접 고르던 모델 컬럼 세 개를 지운다(ADR-20261101 / model-setting).
-- 코드는 더는 읽거나 쓰지 않고, 운영에서 모든 줄이 비어 있음을 확인했다.
-- 여러 컬럼을 한 문장으로 지우는 문법이 MySQL 과 H2 의 MySQL 모드에서 달라 컬럼마다 따로 지운다.
ALTER TABLE conversation DROP COLUMN model_provider;
ALTER TABLE conversation DROP COLUMN model;
ALTER TABLE conversation DROP COLUMN reasoning_effort;
