-- 에이전트 하나를 다중 에이전트 흐름에 묶는다. 비어 있으면 지금처럼 Hermes 를 한 번 부른다.
ALTER TABLE agent ADD COLUMN flow VARCHAR(64) NULL;
