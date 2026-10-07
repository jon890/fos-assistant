package com.bifos.assistant.hermes.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 승인한 실행이 실패했을 때 커넥터가 선언한 오류 코드와 복구 계약이다(ADR-092).
 *
 * <p>대시보드 plugin 이 manifest 의 {@code errors} 표로 걸러 보내지만 Control Plane 도 같은 규칙으로 다시 본다. 코드는 대문자와
 * 숫자와 밑줄로 64자까지, 세부 칸은 넷까지이고 칸 이름은 소문자로 시작하는 32자까지, 값은 절댓값 10억 이하의 정수와
 * boolean 만이다. 규칙에 맞지 않는 칸은 버린다. 글 값을 받지 않으므로 외부 서비스의 오류 원문이 이 길로 들어오지 못한다.
 *
 * @param code 커넥터가 선언한 오류 코드
 * @param details 세부 칸. 값은 {@link Long} 이나 {@link Boolean} 이다. 없으면 빈 map
 * @param recovery 복구 어휘. 선언하지 않았으면 null
 */
public record ConnectorErrorDetail(String code, Map<String, Object> details, ConnectorRecovery recovery) {

    /** 승인 줄의 {@code result_text} 에 저장한 글이 이 형식임을 밝히는 {@code kind} 값이다. 성공 결과 글과 구분한다. */
    public static final String KIND = "connector_error";

    public static final int DETAILS_MAX = 4;

    public static final long DETAIL_INT_MAX = 1_000_000_000L;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final Pattern DETAIL_KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 세부 값은 상한 안의 {@link Long} 과 {@link Boolean} 만 받는다. 다른 값은 저장 글을 만들 수 없어 여기서 막는다. */
    public ConnectorErrorDetail {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
        details.values().forEach(value -> {
            if (!(value instanceof Boolean) && !(value instanceof Long number && withinBound(number))) {
                throw new IllegalArgumentException("connector error detail values are bounded longs or booleans");
            }
        });
    }

    /**
     * 대시보드 실행 경로의 실패 답에서 {@code code}, {@code details}, {@code recovery} 를 읽는다.
     *
     * @return 코드가 없거나 형식에 맞지 않으면 빈 값
     */
    public static Optional<ConnectorErrorDetail> fromAnswer(JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            return Optional.empty();
        }
        JsonNode code = answer.get("code");
        if (code == null || !code.isString() || !CODE.matcher(code.asString()).matches()) {
            return Optional.empty();
        }
        JsonNode recovery = answer.get("recovery");
        return Optional.of(new ConnectorErrorDetail(
                code.asString(),
                details(answer.get("details")),
                recovery != null && recovery.isString()
                        ? ConnectorRecovery.fromWord(recovery.asString()).orElse(null)
                        : null));
    }

    /**
     * 승인 줄의 {@code result_text} 에 저장한 글을 읽는다. {@code kind} 가 {@link #KIND} 인 JSON object 만 읽는다.
     *
     * @return 이 형식이 아니면 빈 값. 성공 결과 글도 빈 값이다
     */
    public static Optional<ConnectorErrorDetail> fromStored(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode stored = MAPPER.readTree(text);
            JsonNode kind = stored == null ? null : stored.get("kind");
            if (kind == null || !kind.isString() || !KIND.equals(kind.asString())) {
                return Optional.empty();
            }
            return fromAnswer(stored);
        } catch (JacksonException ex) {
            return Optional.empty();
        }
    }

    /** {@code result_text} 에 저장할 글이다. {@link #fromStored} 가 읽는다. */
    public String toStored() {
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("kind", KIND);
        stored.put("code", code);
        if (!details.isEmpty()) {
            ObjectNode node = stored.putObject("details");
            details.forEach((key, value) -> {
                if (value instanceof Boolean flag) {
                    node.put(key, flag);
                } else {
                    node.put(key, (Long) value);
                }
            });
        }
        if (recovery != null) {
            stored.put("recovery", recovery.word());
        }
        return stored.toString();
    }

    /** {@code Math.abs} 는 {@code Long.MIN_VALUE} 에서 음수를 돌려주므로 두 끝을 따로 견준다. */
    private static boolean withinBound(long value) {
        return value >= -DETAIL_INT_MAX && value <= DETAIL_INT_MAX;
    }

    /** 칸이 넷을 넘으면 모두 버린다. plugin 이 넘겨서는 안 되는 모양이다. 넷 안에서는 규칙에 맞는 칸만 남긴다. */
    private static Map<String, Object> details(JsonNode node) {
        Map<String, Object> found = new LinkedHashMap<>();
        if (node == null || !node.isObject() || node.size() > DETAILS_MAX) {
            return found;
        }
        node.properties().forEach(entry -> {
            if (!DETAIL_KEY.matcher(entry.getKey()).matches()) {
                return;
            }
            JsonNode value = entry.getValue();
            if (value.isBoolean()) {
                found.put(entry.getKey(), value.booleanValue());
            } else if (value.isIntegralNumber() && value.canConvertToLong() && withinBound(value.longValue())) {
                found.put(entry.getKey(), value.longValue());
            }
        });
        return found;
    }
}
