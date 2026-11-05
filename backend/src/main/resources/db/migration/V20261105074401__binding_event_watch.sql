-- 사건 감지(ADR-20261105 / event-watch)의 켜짐과 상태를 바인딩 줄에 둔다. 새 테이블을 만들지 않는다.
-- 컬럼의 뜻은 backend/docs/data-schema.md 의 「agent_connector_binding」 이 갖는다.
ALTER TABLE agent_connector_binding ADD COLUMN event_watch_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '사건 감지를 켰는가. 같은 연결에서 바인딩 하나만 참이다';
ALTER TABLE agent_connector_binding ADD COLUMN event_watch_state TEXT NULL COMMENT '켤 때 만들고 확인마다 덮어쓰는 사건 감지 상태 JSON 글. 확인 지점, 겹침 구간의 식별자, 마지막 결과, 연속 실패, 하루 실행 수, 미룬 알림';
