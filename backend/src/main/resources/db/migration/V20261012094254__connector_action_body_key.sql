-- 기존 본문 칸은 유지하고 key 식별자만 더한다. 평문 이관은 애플리케이션이 사용자 key로 수행한다.
ALTER TABLE connector_action ADD (
    args_key_id BIGINT NULL,
    result_key_id BIGINT NULL
);
