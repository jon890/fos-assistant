-- 표준 플러그인(schema 3) 연결의 마지막 확인에서 MCP 서버가 낸 도구 이름과 읽기 표시를 둔다(ADR-20261101 / plugin-without-assistant-json).
-- 읽기 표시가 참인 도구만 첫 승인 뒤 상시 허락을 줄 수 있다. 새 표를 만들지 않고 연결 행의 칸 하나로 둔다.
ALTER TABLE connector_connection ADD COLUMN listed_tools TEXT NULL
    COMMENT '표준 플러그인 연결의 마지막 확인에서 MCP 서버가 낸 도구의 JSON 객체 {"도구 이름": readOnlyHint 가 참인가}. NULL 이면 표준 플러그인이 아니거나 도구 목록으로 확인한 적이 없다';
