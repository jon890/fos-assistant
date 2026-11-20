package com.bifos.assistant.chat.artifact.infra;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 링크 사진 받기가 쓰는 작은 해석들이다. 페이지의 대표 사진 meta 찾기, 주소 풀기, 사진 머리 바이트 확인을 한다.
 *
 * <p>HTML 파서 의존성을 더하지 않는다(설계 generated-image DECISION20). 대표 사진은 meta 태그 하나라 태그만 훑는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class LinkPageParts {
    /** 대표 사진 meta 의 이름이다. 앞의 것이 먼저다. */
    private static final List<String> PREVIEW_NAMES = List.of("og:image", "og:image:secure_url", "twitter:image");
    /**
     * meta 태그 하나의 길이 상한이다. 정규식으로 태그를 찾지 않는다. 닫히지 않는 {@code <meta} 가 많은 페이지에서 정규식이 입력 길이의 제곱으로
     * 느려지고, 제한 시간이 지나도 {@code Matcher} 는 인터럽트를 보지 않아 받기 스레드를 붙잡는다.
     */
    static final int MAX_TAG_CHARS = 4096;
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});");
    private static final Map<String, String> NAMED = Map.of(
        "amp", "&", "quot", "\"", "apos", "'", "lt", "<", "gt", ">", "nbsp", " ");
    /** 받는 사진 형식이다. 첨부 사진과 같고 이미지 서비스가 받는다. */
    static final List<String> IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp", "image/gif");


    /** 페이지 글에서 대표 사진 주소의 글을 찾는다. 엔티티는 푼다. 없으면 빈 값이다. */
    static Optional<String> previewImage(String html) {
        String found = null;
        int rank = PREVIEW_NAMES.size();
        int from = 0;
        // 여기까지는 '>' 가 없다고 이미 본 자리다. 닫히지 않은 태그가 이어져도 같은 구간을 다시 훑지 않아 전체가 선형이다.
        int noCloseUntil = 0;
        while (true) {
            int start = indexOfMeta(html, from);
            if (start < 0) {
                break;
            }
            int afterName = start + "<meta".length();
            from = afterName;
            if (afterName < html.length() && !isTagBoundary(html.charAt(afterName))) {
                continue;
            }
            // '>' 는 태그 길이 상한 안에서만 찾는다. 페이지 끝까지 찾으면 닫히지 않은 태그마다 끝까지 훑어 다시 제곱이 된다.
            int limit = start + MAX_TAG_CHARS;
            int end = indexWithin(html, '>', Math.max(afterName, noCloseUntil), limit);
            if (end < 0) {
                noCloseUntil = Math.max(noCloseUntil, Math.min(html.length(), limit));
                continue;
            }
            from = end + 1;
            Candidate candidate = candidate(html.substring(start, end + 1));
            int index = candidate.name() == null ? -1 : PREVIEW_NAMES.indexOf(candidate.name());
            if (index >= 0 && index < rank && candidate.content() != null && !candidate.content().isBlank()) {
                found = candidate.content();
                rank = index;
            }
        }
        return Optional.ofNullable(found).map(LinkPageParts::decodeEntities).map(String::trim);
    }


    /**
     * 원문에서 {@code <meta} 를 대소문자 없이 찾는다. 소문자 사본을 만들지 않는다. {@code İ}(U+0130)처럼 소문자로 바꾸면 길이가 달라지는 글자가
     * 앞에 있으면 사본의 위치로 원문을 잘라 태그가 어긋나기 때문이다.
     */
    private static int indexOfMeta(String html, int from) {
        for (int i = from; i + "<meta".length() <= html.length(); i++) {
            if (html.charAt(i) == '<' && html.regionMatches(true, i, "<meta", 0, "<meta".length())) {
                return i;
            }
        }
        return -1;
    }


    private static int indexWithin(String text, char wanted, int from, int limit) {
        int stop = Math.min(text.length(), limit);
        for (int i = from; i < stop; i++) {
            if (text.charAt(i) == wanted) {
                return i;
            }
        }
        return -1;
    }


    private static boolean isTagBoundary(char character) {
        return Character.isWhitespace(character) || character == '>' || character == '/';
    }


    /** 길이 상한 안의 태그 하나에서 이름({@code property} 나 {@code name})과 {@code content} 를 읽는다. */
    private static Candidate candidate(String tag) {
        String name = null;
        String content = null;
        Matcher attribute = ATTRIBUTE.matcher(tag);
        while (attribute.find()) {
            String key = attribute.group(1).toLowerCase(Locale.ROOT);
            String value = attribute.group(2) != null
                ? attribute.group(2)
                : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
            if (("property".equals(key) || "name".equals(key)) && name == null) {
                name = value.trim().toLowerCase(Locale.ROOT);
            } else if ("content".equals(key) && content == null) {
                content = value;
            }
        }
        return new Candidate(name, content);
    }


    private record Candidate(String name, String content) {
    }


    static String decodeEntities(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(1);
            String replacement;
            if (body.startsWith("#x") || body.startsWith("#X")) {
                replacement = codePoint(Integer.parseInt(body.substring(2), 16), matcher.group());
            } else if (body.startsWith("#")) {
                replacement = codePoint(Integer.parseInt(body.substring(1)), matcher.group());
            } else {
                replacement = NAMED.getOrDefault(body.toLowerCase(Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }


    private static String codePoint(int value, String original) {
        return Character.isValidCodePoint(value) && value != 0 ? new String(Character.toChars(value)) : original;
    }


    /**
     * 페이지나 넘기기가 준 주소 글을 현재 주소 기준으로 푼다. {@code //} 는 https 로 풀고, ASCII 밖 문자와 공백은 UTF-8 퍼센트 인코딩한다.
     * 조각({@code #...})은 버린다.
     *
     * @return 풀지 못하면 빈 값이다
     */
    static Optional<URI> resolve(URI base, String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String text = reference.trim();
        int hash = text.indexOf('#');
        if (hash >= 0) {
            text = text.substring(0, hash);
        }
        if (text.startsWith("//")) {
            text = "https:" + text;
        }
        try {
            URI resolved = base.resolve(new URI(asciiEncoded(text)));
            return Optional.of(resolved);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }


    /** ASCII 밖 문자, 공백, 제어 문자를 UTF-8 퍼센트 인코딩한다. 이미 있는 {@code %XX} 는 그대로 둔다. */
    static String asciiEncoded(String text) {
        StringBuilder result = new StringBuilder();
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            if (unsigned <= 0x20 || unsigned >= 0x7f || "\"<>\\^`{|}".indexOf(unsigned) >= 0) {
                result.append('%').append(String.format(Locale.ROOT, "%02X", unsigned));
            } else {
                result.append((char)unsigned);
            }
        }
        return result.toString();
    }


    /** 응답 머리의 형식이 받는 사진 형식이고 머리 바이트도 그 형식인가. */
    static boolean matchesImage(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(bytes, new int[] {0xff, 0xd8, 0xff});
            case "image/gif" -> startsWith(bytes, "GIF87a".chars().toArray()) || startsWith(bytes, "GIF89a".chars().toArray());
            case "image/webp" -> bytes.length >= 12
                && startsWith(bytes, "RIFF".chars().toArray())
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }


    /**
     * 응답 머리가 사진 형식을 다른 이름(예: {@code multipart/form-data}, {@code application/octet-stream})으로 적은 경우에 머리 바이트로 형식을
     * 정한다. 받는 네 형식만 판정하고, 머리 바이트로 알 수 없는 형식(SVG 등)은 빈 값이다.
     */
    static Optional<String> sniffImageType(byte[] bytes) {
        return IMAGE_TYPES.stream().filter(type -> matchesImage(type, bytes)).findFirst();
    }


    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }


    static String mediaType(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
