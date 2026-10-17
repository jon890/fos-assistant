-- 기억 운영 집계 쿼리다. 지표의 이름과 뜻은 docs/features/memory.md 의 「운영 집계」 가 갖는다.
-- 읽기만 하고 제목과 본문 컬럼을 읽지 않는다. 모두 최근 28일을 센다.
-- 비율은 나누지 않고 분자와 분모를 따로 낸다. DB 마다 나눗셈의 자릿수가 달라서다.
-- 쿼리마다 인자가 없다. 접속과 실행 방법은 운영 저장소가 갖는다.
-- 맞는지는 MemoryOpsMetricsSqlTest 와 MemoryOpsMetricsSqlMysqlTest 가 합성 줄로 확인한다.

-- name: layer_items
SELECT source, body_mode, selector, COUNT(*) AS items
FROM execution_context_source
WHERE LEFT(source, 7) = 'MEMORY_'
  AND created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
GROUP BY source, body_mode, selector
ORDER BY source, body_mode, selector;

-- name: index_read_rate
SELECT COUNT(*) AS indexed_items,
       COALESCE(SUM(CASE WHEN EXISTS (
           SELECT 1 FROM execution_context_source r
           WHERE r.execution_id = i.execution_id AND r.source_ref = i.source_ref AND r.source = 'MEMORY_READ')
         THEN 1 ELSE 0 END), 0) AS read_items
FROM (
  SELECT DISTINCT execution_id, source_ref
  FROM execution_context_source
  WHERE source = 'MEMORY_INDEX' AND body_mode = 'TITLE_ONLY'
    AND created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
) i;

-- name: memory_read_call_rate
SELECT COUNT(*) AS root_executions,
       COALESCE(SUM(CASE WHEN EXISTS (
           SELECT 1 FROM execution_event ev
           WHERE ev.execution_id = e.id AND ev.event_type = 'TOOL_STARTED' AND ev.tool_name LIKE '%memory_read')
         THEN 1 ELSE 0 END), 0) AS with_memory_read
FROM agent_execution e
WHERE e.parent_execution_id IS NULL AND e.conversation_id IS NOT NULL
  AND e.started_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP);

-- name: inline_correction_rate
SELECT COUNT(*) AS inline_items,
       COALESCE(SUM(CASE WHEN EXISTS (
           SELECT 1 FROM memory_revision r
           WHERE CONCAT('memory:', r.memory_id) = f.source_ref AND r.changed_at > f.first_loaded_at)
         THEN 1 ELSE 0 END), 0) AS corrected_items
FROM (
  SELECT source_ref, MIN(created_at) AS first_loaded_at
  FROM execution_context_source
  WHERE source IN ('MEMORY_ALWAYS', 'MEMORY_FACTS', 'MEMORY_SEARCH', 'MEMORY_READ') AND body_mode = 'INLINE'
    AND created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
  GROUP BY source_ref
) f;

-- name: never_loaded_items
SELECT COUNT(*) AS eligible_items,
       COALESCE(SUM(CASE WHEN NOT EXISTS (
           SELECT 1 FROM execution_context_source s
           WHERE s.source_ref = CONCAT('memory:', m.id)
             AND s.created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP))
         THEN 1 ELSE 0 END), 0) AS never_loaded
FROM memory m
WHERE m.status = 'ACCEPTED' AND m.retrieval <> 'ARCHIVE' AND m.entry_type <> 'SOURCE'
  AND m.created_at < TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP);

-- name: proposal_outcomes
-- 상태를 CONCAT 으로 문자열로 바꾼다. H2 의 enum 컬럼은 'DELETED' 를 받지 못한다.
SELECT CASE WHEN m.id IS NULL THEN 'DELETED' ELSE CONCAT(m.status, '') END AS status, COUNT(*) AS proposals
FROM memory_capture c
LEFT JOIN memory m ON m.id = c.memory_id
WHERE c.kind = 'PROPOSED' AND c.created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
GROUP BY CASE WHEN m.id IS NULL THEN 'DELETED' ELSE CONCAT(m.status, '') END
ORDER BY status;

-- name: undo_rate
SELECT COUNT(*) AS captures, COUNT(undone_at) AS undone
FROM memory_capture
WHERE kind IN ('CREATED', 'UPDATED') AND created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP);

-- name: regenerate_by_memory
-- 「참고한 기억」 이 있는 답과 없는 답을 견주는 관찰 비교다. 인과가 아니다.
SELECT a.with_memory, COUNT(*) AS answers, SUM(a.regenerated) AS regenerated
FROM (
  SELECT CASE WHEN EXISTS (
             SELECT 1 FROM execution_context_source s
             WHERE s.execution_id = m.execution_id AND LEFT(s.source, 7) = 'MEMORY_' AND s.body_mode = 'INLINE')
           THEN 1 ELSE 0 END AS with_memory,
         CASE WHEN EXISTS (SELECT 1 FROM chat_message n WHERE n.replaces_message_id = m.id)
           THEN 1 ELSE 0 END AS regenerated
  FROM chat_message m
  WHERE m.role = 'ASSISTANT' AND m.execution_id IS NOT NULL
    AND m.created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
) a
GROUP BY a.with_memory
ORDER BY a.with_memory;

-- name: context_size
SELECT COUNT(*) AS executions,
       AVG(context_chars) AS avg_context_chars,
       MAX(context_chars) AS max_context_chars,
       COALESCE(SUM(context_omitted_items), 0) AS omitted_items
FROM agent_execution
WHERE parent_execution_id IS NULL AND conversation_id IS NOT NULL
  AND started_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP);

-- name: memory_links
-- 기간 안에 만든 기억 연결을 종류, 만든 쪽, 지금 상태별로 센다. 상태를 CONCAT 으로 문자열로 바꾸는 까닭은 proposal_outcomes 와 같다.
SELECT CONCAT(type, '') AS link_type, CONCAT(origin, '') AS origin, CONCAT(status, '') AS status, COUNT(*) AS links
FROM memory_link
WHERE created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)
GROUP BY CONCAT(type, ''), CONCAT(origin, ''), CONCAT(status, '')
ORDER BY link_type, origin, status;

-- name: auto_link_revoked
-- 코드가 만든 연결 가운데 사람이 끊었거나 거절한 줄이다. 자동 연결이 틀린 정도를 표본으로 본다.
SELECT COUNT(*) AS auto_links, COALESCE(SUM(CASE WHEN status = 'REVOKED' THEN 1 ELSE 0 END), 0) AS revoked
FROM memory_link
WHERE origin = 'AUTO' AND created_at >= TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP);
