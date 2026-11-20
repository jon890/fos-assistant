package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.crypto.domain.SecretPrefix;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectionFieldsConverter;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 요청이 준 칸 값을 manifest 로 검사하고, 저장할 모양으로 바꾼다.
 *
 * <p>비밀 칸의 원문이 저장되지 않게 하는 규칙이 여기 한 곳에 있다(ADR-043).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorValues {
    /** 비밀이 아닌 칸 값의 상한이다. 칸 값은 {@code fields} 열 하나에 JSON 으로 함께 들어간다. */
    private static final int MAX_STORED_VALUE_LENGTH = 500;

    /** 비밀 칸 값의 상한이다. 저장하지 않지만 대시보드와 env 파일로 그대로 넘어간다. */
    private static final int MAX_SECRET_VALUE_LENGTH = 4096;

    private static final ConnectionFieldsConverter COLUMN = new ConnectionFieldsConverter();


    /**
     * 요청의 값을 manifest 로 검사하고 채운 칸만 남긴다.
     *
     * <p>비운 선택 칸은 결과에 넣지 않는다. 오류 메시지에는 칸 이름과 값을 싣지 않는다.
     *
     * @param requireAll 등록이면 참. 필수 칸이 모두 있어야 하고, 저장할 칸 값 전체가 {@code fields} 열에 들어가야 한다.
     * 외부에 반영한 뒤 저장에서 실패하지 않도록 여기서 먼저 본다
     */
    static Map<String, String> validated(ConnectorManifest manifest, Map<String, String> values, boolean requireAll) {
        return validated(manifest, values, requireAll, ConnectionAuth.MANUAL);
    }


    /**
     * 단추 연결을 시작할 때 함께 받은 단추 밖 칸 값을 등록과 같이 검사한다. 단추가 대신하는 칸({@code oauth.fields})은 값이 오면 거절하고
     * 필수 검사에서 뺀다.
     */
    static Map<String, String> validated(ConnectorManifest manifest, Map<String, String> values, ConnectionAuth auth) {
        return validated(manifest, values, true, auth);
    }


    private static Map<String, String> validated(
        ConnectorManifest manifest, Map<String, String> values, boolean requireAll, ConnectionAuth auth) {
        List<String> replaced = auth.usesOAuth() ? manifest.oauth().fields() : List.of();
        Map<String, String> given = values == null ? Map.of() : values;
        Map<String, ConnectorField> fields = manifest.fields().stream().collect(Collectors.toMap(ConnectorField::key, field -> field));
        if (!fields.keySet().containsAll(given.keySet()) || replaced.stream().anyMatch(given::containsKey)) {
            throw ConnectorErrors.invalid();
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        for (ConnectorField field : manifest.fields()) {
            String value = given.get(field.key());
            if (value == null || value.isBlank()) {
                if (requireAll && field.required() && !replaced.contains(field.key())) {
                    throw ConnectorErrors.invalid();
                }
                continue;
            }
            int limit = field.secret() ? MAX_SECRET_VALUE_LENGTH : MAX_STORED_VALUE_LENGTH;
            if (!matches(field, value) || value.length() > limit) {
                throw ConnectorErrors.invalid();
            }
            accepted.put(field.key(), value);
        }
        if (requireAll
            && COLUMN.convertToDatabaseColumn(stored(manifest, accepted, auth)).length() > ConnectorConnection.FIELDS_LENGTH) {
            throw ConnectorErrors.invalid();
        }
        return accepted;
    }


    private static boolean matches(ConnectorField field, String value) {
        // 줄바꿈과 NUL 은 env 파일의 다른 줄을 쓰게 만들 수 있다.
        if (value.chars().anyMatch(ch -> ch == '\r' || ch == '\n' || ch == 0)) {
            return false;
        }
        if (field.pattern() == null) {
            return true;
        }
        try {
            return Pattern.compile(field.pattern()).matcher(value).matches();
        } catch (PatternSyntaxException ex) {
            // manifest 의 형식은 대시보드가 쓰는 정규식 문법이다. 여기서 읽지 못하면 대시보드의 검사에 맡긴다.
            return true;
        }
    }


    /**
     * 저장할 칸 값이다.
     *
     * <p>비밀 칸은 {@link SecretPrefix} 가 정한 앞부분만 남긴다. 짧은 값은 아무것도 남기지 않는다.
     */
    static ConnectionFields stored(ConnectorManifest manifest, Map<String, String> accepted) {
        return stored(manifest, accepted, ConnectionAuth.MANUAL);
    }


    /** 연결 방식을 함께 적은 저장할 칸 값이다. */
    static ConnectionFields stored(ConnectorManifest manifest, Map<String, String> accepted, ConnectionAuth auth) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> secretPrefixes = new LinkedHashMap<>();
        for (ConnectorField field : manifest.fields()) {
            String value = accepted.get(field.key());
            if (value == null) {
                continue;
            }
            if (!field.secret()) {
                values.put(field.key(), value);
            } else {
                String prefix = SecretPrefix.of(value);
                if (prefix != null) {
                    secretPrefixes.put(field.key(), prefix);
                }
            }
        }
        return new ConnectionFields(values, secretPrefixes, auth);
    }
}
