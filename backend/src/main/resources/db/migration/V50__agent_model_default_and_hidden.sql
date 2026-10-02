-- 에이전트 기본 모델을 Control Plane 이 갖는다(ADR-054). 세 칸이 모두 비면 Hermes profile 의 값으로 돈다.
-- 모델 이름을 여기 넣지 않는다. 값은 관리자가 화면에서 정한다.
-- 여러 칸을 한 문장으로 더하는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 칸마다 따로 더한다.
ALTER TABLE agent ADD COLUMN default_model_provider VARCHAR(64) NULL;
ALTER TABLE agent ADD COLUMN default_model VARCHAR(128) NULL;
ALTER TABLE agent ADD COLUMN default_reasoning_effort VARCHAR(16) NULL;

-- 그룹이 숨긴 provider 와 모델이다. 목록에 없는 것은 모두 보인다.
-- model 이 빈 문자열이면 그 provider 전체를 숨긴 것이다. NULL 은 유일 제약이 겹침을 막지 못해 쓰지 않는다.
CREATE TABLE model_hidden (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_hidden_group_provider_model (group_id, provider, model)
);
