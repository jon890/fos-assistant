package com.bifos.assistant.hermes.dto;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 승인한 실행의 결과를 모를 때 커넥터가 선언한 오류 코드와 다시 보낼 때 쓰는 식별자다(ADR-20261013 unknown-retry-key).
 *
 * <p>대시보드 plugin 이 manifest 의 {@code errors} 표로 걸러 504 본문에 싣지만 Control Plane 도 같은 규칙으로 다시 본다. 코드는
 * {@link ConnectorErrorDetail} 과 같은 형식이다. 식별자 칸은 하나뿐이고, 칸 이름은 영문자로 시작하는 32자까지, 값은 영문자나 숫자로
 * 시작하고 영문자, 숫자, 밑줄, 하이픈만 쓴 64자까지다. 공백과 {@code @} 가 든 글은 받지 않으므로 오류 원문과 개인 정보가 이 길로
 * 들어오지 못한다.
 *
 * @param code 커넥터가 선언한 오류 코드
 * @param retryKey 식별자 칸 이름. 없으면 null
 * @param retryValue 식별자 값. {@code retryKey} 가 null 이면 null
 */
public record ConnectorUnknownDetail(String code, String retryKey, String retryValue) {


    /** 승인 줄의 {@code result_text} 에 저장한 글이 이 형식임을 밝히는 {@code kind} 값이다. */
    public static final String KIND = "connector_unknown";

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final Pattern RETRY_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    private static final Pattern RETRY_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 형식에 맞지 않는 값은 저장 글을 만들 수 없어 여기서 막는다. */
    public ConnectorUnknownDetail {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("connector unknown code must be a declared error code");
        }
        if ((retryKey == null) != (retryValue == null)
            || retryKey != null && (!RETRY_KEY.matcher(retryKey).matches() || !RETRY_VALUE.matcher(retryValue).matches())) {
            throw new IllegalArgumentException("connector retry identifier must be a short identifier");
        }
    }


    /**
     * 대시보드 실행 경로의 504 본문에서 {@code code} 와 {@code retry} 를 읽는다. {@code retry} 가 칸 하나짜리 객체가 아니거나 형식에
     * 맞지 않으면 식별자만 버리고 코드는 남긴다.
     *
     * @return 코드가 없거나 형식에 맞지 않으면 빈 값
     */
    public static Optional<ConnectorUnknownDetail> fromAnswer(JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            return Optional.empty();
        }
        JsonNode code = answer.get("code");
        if (code == null || !code.isString() || !CODE.matcher(code.asString()).matches()) {
            return Optional.empty();
        }
        JsonNode retry = answer.get("retry");
        if (retry == null || !retry.isObject() || retry.size() != 1) {
            return Optional.of(new ConnectorUnknownDetail(code.asString(), null, null));
        }
        Map.Entry<String, JsonNode> entry = retry.properties().iterator().next();
        JsonNode value = entry.getValue();
        if (!RETRY_KEY.matcher(entry.getKey()).matches()
            || !value.isString()
            || !RETRY_VALUE.matcher(value.asString()).matches()) {
            return Optional.of(new ConnectorUnknownDetail(code.asString(), null, null));
        }
        return Optional.of(new ConnectorUnknownDetail(code.asString(), entry.getKey(), value.asString()));
    }


    /**
     * 승인 줄의 {@code result_text} 에 저장한 글을 읽는다. {@code kind} 가 {@link #KIND} 인 JSON object 만 읽는다.
     *
     * @return 이 형식이 아니면 빈 값
     */
    public static Optional<ConnectorUnknownDetail> fromStored(String text) {
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
        if (retryKey != null) {
            stored.putObject("retry").put(retryKey, retryValue);
        }
        return stored.toString();
    }
}
