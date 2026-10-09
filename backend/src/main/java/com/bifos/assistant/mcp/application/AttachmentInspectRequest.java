package com.bifos.assistant.mcp.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** 원본 조회에 허용하는 첨부 번호와 표시 좌표다. 서명할 인자는 두 정수 표현만 사용한다. */
public record AttachmentInspectRequest(long attachmentId, List<Integer> region) {
    public static AttachmentInspectRequest from(JsonNode body) {
        if (body == null || !body.isObject()
                || !Set.of("attachment_id", "region", "_fos_ctx", "_fos_inspect").containsAll(body.propertyNames())) {
            throw invalid();
        }
        JsonNode id = body.get("attachment_id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw invalid();
        }
        JsonNode value = body.get("region");
        List<Integer> region = null;
        if (value != null && !value.isNull()) {
            if (!value.isArray() || value.size() != 4) {
                throw invalid();
            }
            region = new ArrayList<>();
            for (JsonNode coordinate : value) {
                if (!coordinate.isIntegralNumber() || !coordinate.canConvertToInt() || coordinate.intValue() < 0) {
                    throw invalid();
                }
                region.add(coordinate.intValue());
            }
            if (region.get(2) <= region.get(0) || region.get(3) <= region.get(1)) {
                throw invalid();
            }
            region = List.copyOf(region);
        }
        return new AttachmentInspectRequest(id.longValue(), region);
    }

    public String digest() {
        String coordinates = region == null ? "" : region.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((attachmentId + "\n" + coordinates).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "invalid attachment inspection arguments");
    }
}
