package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기억 운영 집계 쿼리({@code memory-eval/ops-metrics.sql})가 지표마다 세야 할 줄만 세는지 합성 줄로 확인한다. 지표의 뜻은
 * {@code docs/features/memory.md} 의 「운영 집계」 가 갖는다.
 *
 * <p>같은 데이터베이스를 쓰는 다른 검사가 남긴 줄이 있을 수 있어, 줄을 넣기 전과 뒤에 쿼리를 돌려 그 차이로 견준다. 넣는 줄은 이 검사만 쓰는 큰
 * 번호를 쓰고 끝나면 지운다.
 */
@BackendIntegrationTest
class MemoryOpsMetricsSqlTest {

    static final String RESOURCE = "/memory-eval/ops-metrics.sql";

    private static final List<String> NAMES = List.of(
        "layer_items",
        "index_read_rate",
        "memory_read_call_rate",
        "inline_correction_rate",
        "never_loaded_items",
        "proposal_outcomes",
        "undo_rate",
        "regenerate_by_memory",
        "context_size",
        "memory_links",
        "auto_link_revoked");

    /** 이 검사가 넣는 줄의 번호는 여기서 시작한다. 다른 검사가 쓰는 번호와 겹치지 않게 크게 잡는다. */
    private static final long BASE = 880_000_000L;

    private static final Pattern NAME_LINE = Pattern.compile("^-- name: ([a-z_]+)$", Pattern.MULTILINE);

    private static final Pattern TEXT_COLUMN = Pattern.compile("\\b(title|content)\\b");

    @Autowired
    JdbcTemplate jdbc;

    private final Map<String, List<Long>> inserted = new LinkedHashMap<>();


    @AfterEach
    void tearDown() {
        List<String> tables = new ArrayList<>(inserted.keySet());
        Collections.reverse(tables);
        for (String table : tables) {
            String key = switch (table) {
                case "execution_context_source" -> "execution_id";
                case "memory_revision" -> "memory_id";
                default -> "id";
            };
            List<Long> ids = new ArrayList<>(inserted.get(table));
            Collections.reverse(ids);
            ids.forEach(id -> jdbc.update("DELETE FROM " + table + " WHERE " + key + " = ?", id));
        }
        inserted.clear();
    }


    @Test
    @DisplayName("쿼리 파일은 정해진 열한 지표를 갖고 제목과 본문 컬럼을 읽지 않는다")
    void declaresElevenMetricsWithoutTextColumns() {
        String sql = load();

        assertThat(queries(sql).keySet()).containsExactlyElementsOf(NAMES);
        String withoutComments = sql.lines().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        assertThat(TEXT_COLUMN.matcher(withoutComments).find()).as("제목이나 본문 컬럼을 읽는 줄").isFalse();
    }


    @Test
    @DisplayName("지표마다 기간 안의 해당 줄만 세고 기간 밖과 다른 종류의 줄은 세지 않는다")
    void countsOnlyRowsEachMetricDefines() {
        Map<String, List<Map<String, Object>>> before = runAll();
        seed();
        Map<String, List<Map<String, Object>>> after = runAll();

        assertThat(delta(before, after, "layer_items", "MEMORY_INDEX|TITLE_ONLY|index-id-v1", "items")).isEqualTo(2);
        assertThat(delta(before, after, "layer_items", "MEMORY_FACTS|INLINE|facts-recent-v1", "items")).isEqualTo(1);
        assertThat(delta(before, after, "layer_items", "MEMORY_READ|INLINE|null", "items")).isEqualTo(1);
        assertThat(delta(before, after, "layer_items", "MEMORY_SEARCH|INLINE|search-keyword-v1", "items")).isEqualTo(1);

        assertThat(delta(before, after, "index_read_rate", "", "indexed_items")).isEqualTo(2);
        assertThat(delta(before, after, "index_read_rate", "", "read_items")).isEqualTo(1);

        assertThat(delta(before, after, "memory_read_call_rate", "", "root_executions")).isEqualTo(2);
        assertThat(delta(before, after, "memory_read_call_rate", "", "with_memory_read")).isEqualTo(1);

        // 질문과 관련된 기억 구역에 실린 unreadMemory 도 본문을 실은 항목으로 센다
        assertThat(delta(before, after, "inline_correction_rate", "", "inline_items")).isEqualTo(3);
        assertThat(delta(before, after, "inline_correction_rate", "", "corrected_items")).isEqualTo(1);

        assertThat(delta(before, after, "never_loaded_items", "", "eligible_items")).isEqualTo(3);
        assertThat(delta(before, after, "never_loaded_items", "", "never_loaded")).isEqualTo(1);

        assertThat(delta(before, after, "proposal_outcomes", "ACCEPTED", "proposals")).isEqualTo(1);
        assertThat(delta(before, after, "proposal_outcomes", "DELETED", "proposals")).isEqualTo(1);

        assertThat(delta(before, after, "undo_rate", "", "captures")).isEqualTo(2);
        assertThat(delta(before, after, "undo_rate", "", "undone")).isEqualTo(1);

        assertThat(delta(before, after, "regenerate_by_memory", "1", "answers")).isEqualTo(1);
        assertThat(delta(before, after, "regenerate_by_memory", "1", "regenerated")).isEqualTo(1);
        assertThat(delta(before, after, "regenerate_by_memory", "0", "answers")).isEqualTo(2);
        assertThat(delta(before, after, "regenerate_by_memory", "0", "regenerated")).isEqualTo(0);

        assertThat(delta(before, after, "context_size", "", "executions")).isEqualTo(2);
        assertThat(delta(before, after, "context_size", "", "omitted_items")).isEqualTo(2);

        assertThat(delta(before, after, "memory_links", "SUPERSEDES|AUTO|PROPOSED", "links")).isEqualTo(1);
        assertThat(delta(before, after, "memory_links", "RELATED|AUTO|ACTIVE", "links")).isEqualTo(1);
        assertThat(delta(before, after, "memory_links", "RELATED|AUTO|REVOKED", "links")).isEqualTo(1);
        assertThat(delta(before, after, "memory_links", "RELATED|AGENT|ACTIVE", "links")).isEqualTo(1);

        assertThat(delta(before, after, "auto_link_revoked", "", "auto_links")).isEqualTo(3);
        assertThat(delta(before, after, "auto_link_revoked", "", "revoked")).isEqualTo(1);
    }


    /**
     * 지표마다 세는 줄과 세지 않는 줄을 넣는다. 「안」 은 하루 전, 「밖」 은 40일 전이다. 기간이 28일이라 DB 와 JVM 의 시간대 차이에 흔들리지
     * 않는다.
     */
    private void seed() {
        Instant in = Instant.now().minus(Duration.ofDays(1));
        Instant out = Instant.now().minus(Duration.ofDays(40));
        long conversation = BASE + 1;
        insert("conversation", Map.of("id", conversation, "user_id", BASE, "purpose", "CHAT", "created_at", at(in)));

        long root = BASE + 10;
        long rootWithRead = BASE + 11;
        long child = BASE + 12;
        long oldRoot = BASE + 13;
        execution(root, conversation, null, in, 100, 2);
        execution(rootWithRead, conversation, null, in, 300, 0);
        execution(child, conversation, root, in, 50, 5);
        execution(oldRoot, conversation, null, out, 900, 9);

        long readMemory = BASE + 20;
        long unreadMemory = BASE + 21;
        long correctedMemory = BASE + 22;
        long steadyMemory = BASE + 23;
        long newMemory = BASE + 24;
        long idleMemory = BASE + 25;
        source(rootWithRead, 0, "MEMORY_INDEX", readMemory, "TITLE_ONLY", "index-id-v1", in);
        source(rootWithRead, 1, "MEMORY_INDEX", unreadMemory, "TITLE_ONLY", "index-id-v1", in);
        source(rootWithRead, 2, "MEMORY_READ", readMemory, "INLINE", null, in);
        source(rootWithRead, 3, "MEMORY_SEARCH", unreadMemory, "INLINE", "search-keyword-v1", in);
        source(root, 0, "MEMORY_FACTS", correctedMemory, "INLINE", "facts-recent-v1", in);
        source(root, 1, "OTHER_SOURCE", steadyMemory, "INLINE", null, in);
        source(oldRoot, 0, "MEMORY_ALWAYS", steadyMemory, "INLINE", "always-id-v1", out);

        event(BASE + 30, rootWithRead, "TOOL_STARTED", "mcp__fos__memory_read", in);
        event(BASE + 31, root, "TOOL_STARTED", "mcp__fos__memory_remember", in);
        event(BASE + 32, child, "TOOL_STARTED", "mcp__fos__memory_read", in);

        // 오래전에 만든 항목 셋 가운데 기간 안에 한 번도 실리지 않은 것은 idleMemory 뿐이다. 새 항목과 보관 항목은 대상이 아니다
        memory(readMemory, "ACCEPTED", "SEARCH", out);
        memory(unreadMemory, "ACCEPTED", "SEARCH", out);
        memory(idleMemory, "ACCEPTED", "SEARCH", out);
        memory(correctedMemory, "ACCEPTED", "SEARCH", in);
        memory(steadyMemory, "ACCEPTED", "ARCHIVE", out);
        memory(newMemory, "PROPOSED", "SEARCH", in);
        revision(correctedMemory, Instant.now());

        capture(BASE + 40, newMemory, "PROPOSED", null, in);
        capture(BASE + 41, BASE + 99, "PROPOSED", null, in);
        capture(BASE + 42, correctedMemory, "PROPOSED", null, out);
        capture(BASE + 43, readMemory, "CREATED", in, in);
        capture(BASE + 44, unreadMemory, "UPDATED", null, in);
        capture(BASE + 45, unreadMemory, "CREATED", out, out);
        // newMemory 는 PROPOSED 라 「ACCEPTED」 로 세지 않는다. 받아들인 제안 하나를 더 둔다
        capture(BASE + 46, correctedMemory, "PROPOSED", null, in);

        long answerWithMemory = BASE + 50;
        long answerWithout = BASE + 51;
        long regenerated = BASE + 52;
        message(answerWithMemory, conversation, "ASSISTANT", root, null, in);
        message(answerWithout, conversation, "ASSISTANT", child, null, in);
        message(regenerated, conversation, "ASSISTANT", child, answerWithMemory, in);
        message(BASE + 53, conversation, "USER", null, null, in);

        // 기간 안의 자동 연결 셋 가운데 사람이 끊은 것은 하나다. 에이전트가 맺은 줄과 기간 밖의 자동 줄은 비율에 들지 않는다
        link(BASE + 60, readMemory, unreadMemory, "SUPERSEDES", "AUTO", "PROPOSED", in);
        link(BASE + 61, readMemory, idleMemory, "RELATED", "AUTO", "ACTIVE", in);
        link(BASE + 62, unreadMemory, idleMemory, "RELATED", "AUTO", "REVOKED", in);
        link(BASE + 63, readMemory, unreadMemory, "RELATED", "AGENT", "ACTIVE", in);
        link(BASE + 64, readMemory, correctedMemory, "RELATED", "AUTO", "ACTIVE", out);
    }


    private void link(long id, long from, long to, String type, String origin, String status, Instant createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("from_memory_id", from);
        row.put("to_memory_id", to);
        row.put("type", type);
        row.put("origin", origin);
        row.put("status", status);
        row.put("created_at", at(createdAt));
        row.put("updated_at", at(createdAt));
        insert("memory_link", row);
    }


    private void execution(long id, long conversation, Long parent, Instant startedAt, long contextChars, int omitted) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("user_id", BASE);
        row.put("conversation_id", conversation);
        row.put("parent_execution_id", parent);
        row.put("cost_mode", "API");
        row.put("status", "SUCCEEDED");
        row.put("event_observation", "OBSERVED");
        row.put("started_at", at(startedAt));
        row.put("finished_at", at(startedAt));
        row.put("context_chars", contextChars);
        row.put("context_omitted_items", omitted);
        insert("agent_execution", row);
    }


    private void source(long execution, int position, String source, long memory, String bodyMode, String selector, Instant at) {
        Map<String, Object> row = new HashMap<>();
        row.put("execution_id", execution);
        row.put("position", position);
        row.put("source", source);
        row.put("source_ref", "memory:" + memory);
        row.put("body_mode", bodyMode);
        row.put("freshness", "FRESH");
        row.put("selector", selector);
        row.put("created_at", at(at));
        insert("execution_context_source", row, execution);
    }


    private void event(long id, long execution, String type, String tool, Instant at) {
        insert("execution_event", Map.of(
            "id", id, "execution_id", execution, "sequence", (int)(id - BASE), "event_type", type, "tool_name", tool,
            "occurred_at", at(at)));
    }


    private void memory(long id, String status, String retrieval, Instant createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("scope", "USER");
        row.put("owner_user_id", BASE);
        row.put("status", status);
        row.put("retrieval", retrieval);
        row.put("entry_type", "MEMORY");
        row.put("sensitivity", "NORMAL");
        row.put("collection", "core");
        row.put("created_at", at(createdAt));
        row.put("updated_at", at(createdAt));
        insert("memory", row);
    }


    private void revision(long memory, Instant changedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("memory_id", memory);
        row.put("revision", 1);
        row.put("change_type", "UPDATED");
        row.put("scope", "USER");
        row.put("collection", "core");
        row.put("entry_type", "MEMORY");
        row.put("status", "ACCEPTED");
        row.put("retrieval", "SEARCH");
        row.put("sensitivity", "NORMAL");
        row.put("changed_at", at(changedAt));
        insert("memory_revision", row, memory);
    }


    private void capture(long id, long memory, String kind, Instant undoneAt, Instant createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("memory_id", memory);
        row.put("user_id", BASE);
        row.put("execution_id", BASE + 10);
        row.put("kind", kind);
        row.put("undone_at", undoneAt == null ? null : at(undoneAt));
        row.put("created_at", at(createdAt));
        insert("memory_capture", row);
    }


    private void message(long id, long conversation, String role, Long execution, Long replaces, Instant createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("conversation_id", conversation);
        row.put("role", role);
        row.put("execution_id", execution);
        row.put("replaces_message_id", replaces);
        row.put("created_at", at(createdAt));
        insert("chat_message", row);
    }


    private void insert(String table, Map<String, Object> values) {
        insert(table, values, (Long)values.get("id"));
    }


    /**
     * 줄 하나를 넣는다. 주어지지 않은 필수 컬럼은 타입에 맞는 빈 값으로 채운다. 엔티티로 만든 스키마와 Flyway 스키마가 필수 컬럼 몇 개에서 달라도 같은 코드로
     * 넣으려는 것이다.
     *
     * @param key 끝나고 지울 때 쓰는 번호
     */
    private void insert(String table, Map<String, Object> values, Long key) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        for (Map<String, Object> column : jdbc.queryForList(
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE UPPER(TABLE_NAME) = UPPER(?) AND TABLE_SCHEMA = SCHEMA() AND IS_NULLABLE = 'NO' "
                + "AND COLUMN_DEFAULT IS NULL",
            table)) {
            String name = String.valueOf(column.get("COLUMN_NAME")).toLowerCase(Locale.ROOT);
            if (!row.containsKey(name) && !"id".equals(name)) {
                row.put(name, emptyOf(String.valueOf(column.get("DATA_TYPE"))));
            }
        }
        List<String> names = new ArrayList<>(row.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
            + names.stream().map(name -> "?").collect(Collectors.joining(", ")) + ")";
        jdbc.update(sql, names.stream().map(row::get).toArray());
        List<Long> keys = inserted.computeIfAbsent(table, ignored -> new ArrayList<>());
        if (!keys.contains(key)) {
            keys.add(key);
        }
    }


    private static Object emptyOf(String dataType) {
        String type = dataType.toUpperCase(Locale.ROOT);
        if (type.contains("BOOL")) {
            return Boolean.FALSE;
        }
        if (type.contains("INT") || type.contains("DEC") || type.contains("NUMERIC") || type.contains("DOUBLE")
            || type.contains("FLOAT") || type.contains("REAL")) {
            return 0;
        }
        if (type.contains("TIME") || type.contains("DATE")) {
            return at(Instant.now());
        }
        return "x";
    }


    private static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }


    private Map<String, List<Map<String, Object>>> runAll() {
        Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
        queries(load()).forEach((name, sql) -> results.put(name, jdbc.queryForList(sql)));
        return results;
    }


    /**
     * 같은 묶음 열쇠의 줄에서 그 컬럼이 넣은 뒤에 얼마나 늘었는지다. 열쇠는 숫자가 아닌 앞쪽 컬럼을 {@code |} 로 이은 것이고, 묶지 않는 쿼리는 빈
     * 문자열이다.
     */
    private static long delta(
        Map<String, List<Map<String, Object>>> before,
        Map<String, List<Map<String, Object>>> after,
        String metric,
        String group,
        String column) {
        return valueOf(after.get(metric), group, column) - valueOf(before.get(metric), group, column);
    }


    private static long valueOf(List<Map<String, Object>> rows, String group, String column) {
        for (Map<String, Object> row : rows) {
            if (groupOf(row).equals(group)) {
                Object value = valueIgnoringCase(row, column);
                return value == null ? 0 : ((Number)value).longValue();
            }
        }
        return 0;
    }


    private static String groupOf(Map<String, Object> row) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (List.of("source", "body_mode", "selector", "link_type", "origin", "status", "with_memory").contains(name)) {
                keys.add(String.valueOf(entry.getValue()));
            }
        }
        return String.join("|", keys);
    }


    private static Object valueIgnoringCase(Map<String, Object> row, String column) {
        return row.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(column))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("결과에 컬럼이 없다: " + column));
    }


    static String load() {
        try (InputStream in = MemoryOpsMetricsSqlTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("쿼리 파일이 없다: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    /** {@code -- name:} 줄로 나눈 쿼리다. 끝의 세미콜론은 뺀다. 순서는 파일 순서다. */
    static Map<String, String> queries(String sql) {
        Matcher matcher = NAME_LINE.matcher(sql);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
            starts.add(matcher.start());
            ends.add(matcher.end());
        }
        Map<String, String> queries = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < names.size() ? starts.get(i + 1) : sql.length();
            String body = sql.substring(ends.get(i), end).strip();
            queries.put(names.get(i), body.endsWith(";") ? body.substring(0, body.length() - 1) : body);
        }
        return queries;
    }
}
