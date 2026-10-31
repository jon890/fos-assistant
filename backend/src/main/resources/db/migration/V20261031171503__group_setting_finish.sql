-- group_setting 으로 옮긴 뒤 옛 칸과 표를 지운다. 칸은 V20261031171501 이 더했고, 옮기는 일과 수 대조는 V20261031171502 가 했다.
-- 기본 키를 바꾸는 두 문장을 하나로 묶는 문법이 H2 의 MySQL 모드에 없어 나눈다.
ALTER TABLE group_setting DROP COLUMN default_tier;
ALTER TABLE group_setting MODIFY COLUMN group_id BIGINT NOT NULL COMMENT '설정을 가진 그룹 식별자';
ALTER TABLE group_setting MODIFY COLUMN setting_key VARCHAR(32) NOT NULL COMMENT '설정 종류. MODEL 은 단계별 모델과 그룹 기본 단계, 숨김 목록이다';
ALTER TABLE group_setting MODIFY COLUMN setting_value TEXT NOT NULL COMMENT '설정 JSON 문자열. 형식과 버전은 Control Plane 이 저장할 때 검사한다';
ALTER TABLE group_setting DROP PRIMARY KEY;
ALTER TABLE group_setting ADD PRIMARY KEY (group_id, setting_key);
DROP TABLE model_tier_definition;
DROP TABLE model_hidden;
