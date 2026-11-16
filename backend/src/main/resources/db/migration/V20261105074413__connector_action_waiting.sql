-- 승인한 실행이 사람이 해야 하는 화면에서 멈춰 같은 승인으로 이어 갈 표식이다. 상태는 기존 status 칸의 WAITING 이고 탭은 handoff_tab 이다.
-- 뜻은 backend/docs/data-schema.md 의 「connector_action」 이, 결정은 docs/adr/ADR-20261113-purchase-checkout.md 가 갖는다. 새 테이블을 만들지 않는다.
ALTER TABLE connector_action ADD COLUMN waiting_since DATETIME(6) NULL COMMENT '처음 사람 화면에서 멈춘 시각(UTC). 멈춘 적 없으면 NULL';
ALTER TABLE connector_action ADD COLUMN resume_step VARCHAR(32) NULL COMMENT '플러그인이 낸 이어 갈 단계. Control Plane 은 뜻을 모른다';
ALTER TABLE connector_action ADD COLUMN resumed_at DATETIME(6) NULL COMMENT '마지막으로 이어 간 시각(UTC). 이어 간 적 없으면 NULL';
