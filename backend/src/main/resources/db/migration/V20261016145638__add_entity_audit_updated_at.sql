-- 기록을 시작하기 전의 변경 시각은 알 수 없으므로 기존 행의 NULL을 보존한다.
ALTER TABLE agent ADD COLUMN updated_at DATETIME(6) NULL COMMENT '저장 변경 시각. 기록 시작 전의 과거 변경 시각은 미상';
ALTER TABLE app_user ADD COLUMN updated_at DATETIME(6) NULL COMMENT '저장 변경 시각. 기록 시작 전의 과거 변경 시각은 미상';
ALTER TABLE allowed_person ADD COLUMN updated_at DATETIME(6) NULL COMMENT '허용 설정의 저장 변경 시각. 기록 시작 전의 과거 변경 시각은 미상';
