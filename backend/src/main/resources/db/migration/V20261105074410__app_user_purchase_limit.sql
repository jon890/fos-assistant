-- 사용자가 정한 구매 상한(한 건, KST 하루)을 app_user 의 컬럼으로 둔다. 새 테이블을 만들지 않는다(ADR-20261113 / purchase-checkout).
-- 컬럼의 뜻은 backend/docs/data-schema.md 의 「app_user」 가 갖는다. 기본 0 은 주문서 준비를 받지 않는 닫힌 상태다.
-- 여러 컬럼을 한 문장으로 더하는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 문장마다 따로 쓴다.
ALTER TABLE app_user ADD COLUMN purchase_limit_order_krw BIGINT NOT NULL DEFAULT 0 COMMENT '구매 한 건 상한. 원 단위이고 상품 금액과 기본 배송비를 더한 예상 합계와 견준다. 0 이면 주문서 준비를 받지 않는다';
ALTER TABLE app_user ADD COLUMN purchase_limit_daily_krw BIGINT NOT NULL DEFAULT 0 COMMENT '구매 KST 하루 상한. 원 단위이고 그날 승인한 주문서 준비의 예상 합계 합과 견준다. 0 이면 주문서 준비를 받지 않는다';
-- 위험도에 PURCHASE 가 더해져 설명을 다시 적는다. 타입과 NULL 여부는 V46 의 정의 그대로다.
ALTER TABLE connector_action MODIFY COLUMN risk VARCHAR(16) NULL COMMENT '판정 당시 위험도 READ 읽기, SENSITIVE 민감 읽기, WRITE 쓰기, DESTRUCTIVE 파괴, FINANCIAL 금융, PURCHASE 구매, 정책을 못 읽으면 NULL';
