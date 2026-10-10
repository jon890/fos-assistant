package com.bifos.assistant.workspace.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 목록에서 마지막으로 보여 준 정렬 키다. 경로 지문은 요청 경로를 구별할 뿐 권한을 주지 않는다.
 * 이름으로 경로를 열지 않으므로 역슬래시와 주소로 열 수 없는 이름도 보존한다.
 */
public record WorkspaceCursor(String pathHash, boolean directory, String name) {

    private static final int MAX_LENGTH = 4_096;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public WorkspaceCursor {
        if (pathHash == null || !pathHash.matches("[0-9a-f]{64}") || !validName(name)) {
            throw invalid();
        }
    }

    /** 없는 인자만 첫 페이지다. 빈 문자열 등 잘못된 인자는 400 이다. */
    public static WorkspaceCursor decode(String raw, WorkspacePath path) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw.isEmpty() || raw.length() > MAX_LENGTH || !raw.matches("[A-Za-z0-9_-]+")) {
                throw invalid();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) {
                throw invalid();
            }
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            JsonNode node = JSON.readTree(text);
            if (!node.isObject()
                    || node.size() != 4
                    || !node.has("version")
                    || !node.get("version").isIntegralNumber()
                    || !node.get("version").canConvertToInt()
                    || node.get("version").asInt() != 1
                    || !node.has("pathHash")
                    || !node.get("pathHash").isString()
                    || !node.has("directory")
                    || !node.get("directory").isBoolean()
                    || !node.has("name")
                    || !node.get("name").isString()) {
                throw invalid();
            }
            WorkspaceCursor cursor = new WorkspaceCursor(
                    node.get("pathHash").asString(),
                    node.get("directory").asBoolean(),
                    node.get("name").asString());
            if (!cursor.pathHash().equals(fingerprint(path))) {
                throw invalid();
            }
            return cursor;
        } catch (CharacterCodingException | RuntimeException ignored) {
            // 원문과 파서 오류에는 파일 이름이 있을 수 있어 원인 예외를 싣지 않는다.
            throw invalid();
        }
    }

    public static String encode(WorkspacePath path, WorkspaceEntry lastShown) {
        WorkspaceCursor cursor = new WorkspaceCursor(
                fingerprint(path), lastShown.kind() == WorkspaceEntryKind.DIRECTORY, lastShown.name());
        byte[] json = JSON.writeValueAsBytes(Map.of(
                "version", 1, "pathHash", cursor.pathHash(), "directory", cursor.directory(), "name", cursor.name()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    /** 디렉터리 먼저, Java 문자열 순서에서 이 키보다 뒤에 있는 항목인지다. */
    public boolean precedes(WorkspaceEntry entry) {
        int kind = Boolean.compare(entry.kind() != WorkspaceEntryKind.DIRECTORY, !directory);
        return kind > 0 || (kind == 0 && entry.name().compareTo(name) > 0);
    }

    private static String fingerprint(WorkspacePath path) {
        return Sha256.hex(path.value());
    }

    private static boolean validName(String name) {
        if (name == null
                || name.isEmpty()
                || name.equals(".")
                || name.equals("..")
                || name.indexOf('/') >= 0
                || name.indexOf('\0') >= 0) {
            return false;
        }
        try {
            return StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(name))
                            .remaining()
                    <= 255;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "workspace cursor is invalid");
    }
}
