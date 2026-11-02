-- 대화에 직접 고른 모델을 저장하던 세 컬럼을 지운다(ADR-20261101 / model-setting).
-- 모델은 그룹 목록과 슬라이더로만 고르게 되어 이 세 컬럼은 읽는 곳도 쓰는 곳도 없다. 운영 조회에서 모든 줄이 비어 있음을 확인했다.
-- 되돌리기 어려운 삭제라 다른 변경과 섞지 않고 이 파일 하나로 둔다.
-- 여러 컬럼을 한 문장으로 지우는 문법이 MySQL 과 H2 의 MySQL 모드에서 달라 컬럼마다 따로 지운다.
ALTER TABLE conversation DROP COLUMN model_provider;
ALTER TABLE conversation DROP COLUMN model;
ALTER TABLE conversation DROP COLUMN reasoning_effort;
