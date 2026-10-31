package com.bifos.assistant.chat.artifact.domain;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * HTML 과 CSS 가 불러오는 다른 파일의 주소를 찾는 제한된 parser 다(#530 PR 2).
 *
 * <p>본문을 고쳐 쓰지 않고 주소만 꺼낸다. 브라우저가 불러오는 자리(사진, 스타일, 프레임, {@code url()},
 * {@code @import}, {@code image-set()})만 본다. 링크({@code <a href>})와 돌지 않는 스크립트는 보지 않는다.
 *
 * <p>찾은 주소는 그 파일이 있는 폴더 기준으로 풀어 대화 폴더 안의 상대 경로로 돌려준다. 외부 주소, {@code data:} 주소,
 * {@code /} 로 시작하는 주소, 대화 폴더 위로 올라가는 주소는 {@link ArtifactBundleRejectedException} 으로 거절한다.
 * 묶음 일부만 보관하지 않으려는 것이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArtifactReferences {

    /** 주소 맨 앞의 scheme 이다. 있으면 외부 주소다. */
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    /** CSS 에서 주소를 담는 자리다. 주석과 주소가 아닌 문자열은 먼저 지운다. */
    private static final Pattern CSS_URL = Pattern.compile(
        "(?i)(?<![\\w-])url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\\s]*))\\s*\\)"
            + "|@import\\s+(?:\"([^\"]*)\"|'([^']*)')");

    /** {@code image-set()} 과 접두사가 붙은 것이다. 안의 문자열이 주소다. */
    private static final Pattern CSS_IMAGE_SET = Pattern.compile("(?i)(?<![\\w])(?:-[a-z]+-)?image-set\\([^)]*");

    /** CSS 주석이다. */
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?(?:\\*/|$)", Pattern.DOTALL);

    /** {@code image-set()} 안의 문자열이다. */
    private static final Pattern CSS_STRING = Pattern.compile("\"([^\"]*)\"|'([^']*)'");

    /** 주소를 담지 않는 CSS 문자열이다. {@code url()} 과 {@code @import} 의 것은 남긴다. */
    private static final Pattern CSS_SKIP = Pattern.compile(
        "(?i)(url\\(\\s*|@import\\s+)?(\"[^\"\\n]*\"|'[^'\\n]*')");

    /**
     * 원문 그대로 읽고 태그를 찾지 않는 요소다. {@code <style>} 은 CSS 로 읽는다.
     *
     * <p>{@code <noscript>} 는 넣지 않는다. 미리보기는 스크립트가 꺼져 있어 그 안이 보통 마크업으로 그려진다.
     */
    private static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title", "xmp");

    /** 요소마다 불러오는 주소를 담는 속성이다. 모든 요소의 {@code style} 속성은 따로 본다. */
    private static final Map<String, Set<String>> LOADING_ATTRIBUTES = Map.ofEntries(
        Map.entry("img", Set.of("src", "srcset")),
        Map.entry("source", Set.of("src", "srcset")),
        Map.entry("video", Set.of("src", "poster")),
        Map.entry("audio", Set.of("src")),
        Map.entry("track", Set.of("src")),
        Map.entry("input", Set.of("src")),
        Map.entry("iframe", Set.of("src")),
        Map.entry("frame", Set.of("src")),
        Map.entry("embed", Set.of("src")),
        Map.entry("object", Set.of("data")),
        Map.entry("body", Set.of("background")),
        Map.entry("table", Set.of("background")),
        Map.entry("td", Set.of("background")),
        Map.entry("th", Set.of("background")),
        Map.entry("image", Set.of("href", "xlink:href")),
        Map.entry("feimage", Set.of("href", "xlink:href")),
        Map.entry("use", Set.of("href", "xlink:href")));

    /**
     * {@code <link>} 가운데 미리보기가 실제로 불러 그리는 {@code rel} 값이다. {@code preload} 는 {@code as} 가 스타일이나
     * 사진일 때만 따라간다. 스크립트나 manifest 를 가리키는 것은 미리보기에 쓰이지 않아 따라가지 않는다.
     */
    private static final Set<String> LOADING_LINK_RELS = Set.of("stylesheet", "icon", "apple-touch-icon", "mask-icon");

    /** 태그 하나다. 이름과 속성 부분을 나눈다. 따옴표 안의 {@code >} 는 태그 끝이 아니다. */
    private static final Pattern TAG = Pattern.compile(
        "<!--.*?(?:-->|$)|<[!?/][^>]*>?|<([a-zA-Z][^\\s/>]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>?", Pattern.DOTALL);

    /** 속성 하나다. 값은 따옴표로 감쌌거나 감싸지 않았다. */
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([^\\s\"'>/=]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]*)))?");

    /** 숫자 문자 참조와 자주 쓰는 이름 참조다. */
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|quot|apos|lt|gt|colon);");


    /**
     * HTML 이 불러오는 파일들이다. 처음 나온 순서를 지키고 같은 경로는 한 번만 준다.
     *
     * @param path 이 HTML 의 대화 폴더 안 상대 경로
     */
    public static List<String> ofHtml(String path, String html) {
        Set<String> found = new LinkedHashSet<>();
        Matcher tag = TAG.matcher(html);
        int from = 0;
        while (from < html.length() && tag.find(from)) {
            from = tag.end();
            if (tag.group(1) == null) {
                continue;
            }
            String name = tag.group(1).toLowerCase(Locale.ROOT);
            onTag(path, name, attributes(tag.group(2)), found);
            if (RAW_TEXT.contains(name)) {
                int close = indexOfIgnoreCase(html, "</" + name, from);
                int end = close < 0 ? html.length() : close;
                if (name.equals("style")) {
                    scanCss(path, html.substring(from, end), found);
                }
                from = end;
            }
        }
        return List.copyOf(found);
    }


    /**
     * CSS 가 불러오는 파일들이다.
     *
     * @param path 이 CSS 의 대화 폴더 안 상대 경로. {@code <style>} 이나 {@code style} 속성이면 그 HTML 의 경로다
     */
    public static List<String> ofCss(String path, String css) {
        Set<String> found = new LinkedHashSet<>();
        scanCss(path, css, found);
        return List.copyOf(found);
    }


    /**
     * 대화 폴더 안의 상대 경로를 {@code a/b.png} 모양으로 고른다. {@code ./} 와 {@code ..} 을 풀고 빈 조각을 지운다.
     *
     * @return 고를 수 없으면(빈 경로, 폴더 자체, {@code /} 로 시작) {@code null}. 폴더 위로 올라가면 거절한다
     */
    public static String normalize(String path) {
        if (path == null || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return null;
        }
        return joinInside(new ArrayList<>(), path);
    }


    /**
     * 주소 하나를 대화 폴더 안의 상대 경로로 푼다.
     *
     * @return 따라갈 것이 없으면(빈 주소, {@code #} 로 시작하는 문서 안 주소) {@code null}
     */
    static String resolve(String from, String reference) {
        String trimmed = reference.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("data:")) {
            throw new ArtifactBundleRejectedException("a data URI is not kept in a bundle");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || SCHEME.matcher(trimmed).find()) {
            throw new ArtifactBundleRejectedException("an outside address is not kept in a bundle");
        }
        String bare = trimmed.split("[?#]", 2)[0];
        if (bare.isEmpty()) {
            return null;
        }
        String decoded;
        try {
            // 주소의 경로 조각은 폼 인코딩이 아니라 + 를 그대로 둔다.
            decoded = URLDecoder.decode(bare.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ArtifactBundleRejectedException("a referenced path is not a plain relative path");
        }
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\0') >= 0) {
            throw new ArtifactBundleRejectedException("a referenced path is not a plain relative path");
        }
        List<String> parts = new ArrayList<>();
        int slash = from.lastIndexOf('/');
        if (slash > 0) {
            parts.addAll(List.of(from.substring(0, slash).split("/")));
            parts.removeIf(String::isEmpty);
        }
        return joinInside(parts, decoded);
    }


    /** 숫자 문자 참조와 자주 쓰는 이름 참조만 푼다. 모르는 이름은 그대로 둔다. */
    static String decodeEntities(String value) {
        return ENTITY.matcher(value).replaceAll(match -> Matcher.quoteReplacement(decodeEntity(match.group(1))));
    }


    /** {@code base} 조각들 뒤에 {@code relative} 를 이어 고른다. 폴더 위로 올라가면 거절한다. */
    private static String joinInside(List<String> parts, String relative) {
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    throw new ArtifactBundleRejectedException("a referenced path leaves the conversation folder");
                }
                parts.removeLast();
                continue;
            }
            parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }


    /** 주석을 지우고 {@code image-set()} 의 문자열을 더한 뒤, 주소가 아닌 문자열을 지우고 {@code url()} 과 {@code @import} 를 본다. */
    private static void scanCss(String from, String css, Set<String> found) {
        String bare = CSS_COMMENT.matcher(css).replaceAll(" ");
        bare = CSS_IMAGE_SET.matcher(bare).replaceAll(set -> {
            Matcher strings = CSS_STRING.matcher(set.group());
            while (strings.find()) {
                addResolved(from, unescapeCss(firstNonNull(strings.group(1), strings.group(2))), found);
            }
            return Matcher.quoteReplacement(CSS_STRING.matcher(set.group()).replaceAll("\"\""));
        });
        bare = CSS_SKIP.matcher(bare).replaceAll(match -> Matcher.quoteReplacement(
            match.group(1) == null ? "\"\"" : match.group()));
        Matcher matcher = CSS_URL.matcher(bare);
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                matcher.group(5));
            addResolved(from, unescapeCss(value), found);
        }
    }


    private static void onTag(String from, String name, List<String[]> attributes, Set<String> found) {
        Set<String> loading = LOADING_ATTRIBUTES.getOrDefault(name, Set.of());
        boolean loadingLink = name.equals("link") && isLoadingLink(attributes);
        for (String[] attribute : attributes) {
            String attributeName = attribute[0];
            if (attributeName.equals("style")) {
                scanCss(from, attribute[1], found);
            } else if (attributeName.equals("srcset") && loading.contains("srcset")) {
                for (String candidate : attribute[1].split(",")) {
                    addResolved(from, candidate.strip().split("\\s+", 2)[0], found);
                }
            } else if (loading.contains(attributeName) || (loadingLink && attributeName.equals("href"))) {
                addResolved(from, attribute[1], found);
            }
        }
    }


    private static boolean isLoadingLink(List<String[]> attributes) {
        List<String> rels = List.of(valueOf(attributes, "rel").toLowerCase(Locale.ROOT).split("\\s+"));
        String as = valueOf(attributes, "as").toLowerCase(Locale.ROOT).strip();
        return rels.stream().anyMatch(LOADING_LINK_RELS::contains)
            || (rels.contains("preload") && (as.equals("style") || as.equals("image")));
    }


    private static String valueOf(List<String[]> attributes, String name) {
        return attributes.stream().filter(attribute -> attribute[0].equals(name)).map(attribute -> attribute[1])
            .findFirst().orElse("");
    }


    private static List<String[]> attributes(String text) {
        List<String[]> attributes = new ArrayList<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(2), matcher.group(3), matcher.group(4), "");
            attributes.add(new String[] {matcher.group(1).toLowerCase(Locale.ROOT), decodeEntities(value)});
        }
        return attributes;
    }


    private static void addResolved(String from, String reference, Set<String> found) {
        String resolved = resolve(from, reference);
        if (resolved != null) {
            found.add(resolved);
        }
    }


    /** {@code \} 를 떼고 다음 글자를 그대로 둔다. 16진수 이스케이프는 풀지 않는다. */
    private static String unescapeCss(String value) {
        return value.replaceAll("\\\\(.)", "$1");
    }


    private static String decodeEntity(String entity) {
        return switch (entity) {
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "lt" -> "<";
            case "gt" -> ">";
            case "colon" -> ":";
            default -> {
                boolean hex = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X');
                int codePoint = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
                yield Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : "\uFFFD";
            }
        };
    }


    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }


    private static int indexOfIgnoreCase(String text, String word, int from) {
        for (int index = from; index <= text.length() - word.length(); index++) {
            if (text.regionMatches(true, index, word, 0, word.length())) {
                return index;
            }
        }
        return -1;
    }
}
