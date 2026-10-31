-- 기억 항목에 자동 보관을 허락하는 표시를 더한다(ADR-20261015 / memory-maintenance). 기존 항목은 모두 거짓이라 자동 보관하지 않는다.
ALTER TABLE memory
    ADD COLUMN archivable BOOLEAN NOT NULL DEFAULT FALSE COMMENT '정비 루프가 이 항목을 자동 보관해도 되는가. 거짓이면 변하지 않는 사실이거나 모르는 것으로 보고 후보로만 보인다';
