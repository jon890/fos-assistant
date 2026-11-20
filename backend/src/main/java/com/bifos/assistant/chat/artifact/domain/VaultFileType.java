package com.bifos.assistant.chat.artifact.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 「내 파일」(보관함)이 받는 형식 하나다(ADR-20261119 / file-space-put, 스펙 workspace REQ4).
 *
 * <p>형식은 확장자로 정한다. 대화 폴더가 화면에 내주는 형식({@code ArtifactPathPolicy})보다 넓고, 그 표는 넓히지 않는다.
 *
 * @param kind 화면이 종류로 나누는 값
 * @param contentType 보관본을 내줄 때의 형식. 글자 집합은 붙이지 않는다
 * @param inline 화면에서 그대로 열어도 되는가. 거짓이면 늘 내려받기로 준다
 * @param magic 머리 바이트를 보는 방법
 */
public record VaultFileType(Kind kind, String contentType, boolean inline, Magic magic) {

    /** 화면의 종류 고르기와 같은 값이다. */
    public enum Kind {
        IMAGE,
        DOCUMENT,
        TABLE
    }

    /** 본문의 앞부분으로 형식을 확인하는 방법이다. */
    public enum Magic {
        PNG,
        JPEG,
        GIF,
        WEBP,
        PDF,
        ZIP,
        /** 글 형식이다. NUL 바이트가 없어야 한다. 한글 윈도우의 CSV 처럼 UTF-8 이 아닌 글도 받는다. */
        TEXT
    }


    private static final String DOWNLOAD = "application/octet-stream";
    /** 화면이 글로 받아 미리 보는 형식이다. 스크립트 없는 머리글과 nosniff 로 내주므로 다른 형식으로 읽히지 않는다. */
    private static final String PLAIN_TEXT = "text/plain";
    private static final int TEXT_PROBE_BYTES = 8192;

    private static final Map<String, VaultFileType> TYPES = Map.ofEntries(
        Map.entry("png", new VaultFileType(Kind.IMAGE, "image/png", true, Magic.PNG)),
        Map.entry("jpg", new VaultFileType(Kind.IMAGE, "image/jpeg", true, Magic.JPEG)),
        Map.entry("jpeg", new VaultFileType(Kind.IMAGE, "image/jpeg", true, Magic.JPEG)),
        Map.entry("gif", new VaultFileType(Kind.IMAGE, "image/gif", true, Magic.GIF)),
        Map.entry("webp", new VaultFileType(Kind.IMAGE, "image/webp", true, Magic.WEBP)),
        Map.entry("html", new VaultFileType(Kind.DOCUMENT, "text/html", true, Magic.TEXT)),
        Map.entry("css", new VaultFileType(Kind.DOCUMENT, "text/css", true, Magic.TEXT)),
        Map.entry("md", new VaultFileType(Kind.DOCUMENT, PLAIN_TEXT, true, Magic.TEXT)),
        Map.entry("txt", new VaultFileType(Kind.DOCUMENT, PLAIN_TEXT, true, Magic.TEXT)),
        Map.entry("pdf", new VaultFileType(Kind.DOCUMENT, DOWNLOAD, false, Magic.PDF)),
        Map.entry("docx", new VaultFileType(Kind.DOCUMENT, DOWNLOAD, false, Magic.ZIP)),
        Map.entry("pptx", new VaultFileType(Kind.DOCUMENT, DOWNLOAD, false, Magic.ZIP)),
        Map.entry("csv", new VaultFileType(Kind.TABLE, PLAIN_TEXT, true, Magic.TEXT)),
        Map.entry("tsv", new VaultFileType(Kind.TABLE, PLAIN_TEXT, true, Magic.TEXT)),
        Map.entry("xlsx", new VaultFileType(Kind.TABLE, DOWNLOAD, false, Magic.ZIP)));


    /** 경로의 확장자로 형식을 찾는다. 받지 않는 형식이면 빈 값이다. */
    public static Optional<VaultFileType> of(String path) {
        if (path == null) {
            return Optional.empty();
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }


    /** 본문이 이 형식의 머리 바이트를 갖는가. 이름만 바꾼 다른 형식의 파일을 받지 않으려는 것이다. */
    public boolean matches(byte[] body) {
        return switch (magic) {
            case PNG -> startsWith(body, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case JPEG -> startsWith(body, 0, 0xFF, 0xD8, 0xFF);
            case GIF -> startsWith(body, 0, 'G', 'I', 'F', '8');
            case WEBP -> startsWith(body, 0, 'R', 'I', 'F', 'F') && startsWith(body, 8, 'W', 'E', 'B', 'P');
            case PDF -> startsWith(body, 0, '%', 'P', 'D', 'F', '-');
            case ZIP -> startsWith(body, 0, 'P', 'K', 0x03, 0x04);
            case TEXT -> noNul(body);
        };
    }


    private static boolean noNul(byte[] body) {
        int end = Math.min(body.length, TEXT_PROBE_BYTES);
        for (int index = 0; index < end; index++) {
            if (body[index] == 0) {
                return false;
            }
        }
        return true;
    }


    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
