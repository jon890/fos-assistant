-- 사용자마다 한 줄이던 주문 상한을 app_user 의 컬럼으로, 승인 줄마다 한 줄이던 상한 변경을 connector_action 의 컬럼으로 옮긴다.
-- 컬럼의 뜻은 backend/docs/data-schema.md 의 app_user 와 connector_action 이 갖는다. 줄은 다음 파일이 옮기고, 옛 표는 그다음 파일이 지운다.
-- 여러 컬럼을 한 문장으로 더하는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 문장마다 따로 쓴다.

ALTER TABLE app_user ADD COLUMN limit_order_amount_krw DECIMAL(20, 6) NULL;
ALTER TABLE app_user ADD COLUMN limit_order_amount_usd DECIMAL(20, 6) NULL;
ALTER TABLE app_user ADD COLUMN limit_order_quantity DECIMAL(20, 6) NULL;
ALTER TABLE app_user ADD COLUMN limit_daily_amount_krw DECIMAL(20, 6) NULL;
ALTER TABLE app_user ADD COLUMN limit_daily_amount_usd DECIMAL(20, 6) NULL;
ALTER TABLE app_user ADD COLUMN limit_updated_at DATETIME(6) NULL;

ALTER TABLE connector_action ADD COLUMN limit_kind VARCHAR(32) NULL;
ALTER TABLE connector_action ADD COLUMN limit_requested DECIMAL(20, 6) NULL;
ALTER TABLE connector_action ADD COLUMN limit_previous DECIMAL(20, 6) NULL;
