package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.bifos.assistant.hermes.dto.ConnectorAppearance;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConnectorAppearancesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LINK = "https://notes.example.test/about";
    private static final int ICON_MAX_BYTES = 32 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @DisplayName("SVG 와 PNG 아이콘은 data URL 로, https 링크는 그대로 담는다")
    @Test
    void readsSvgAndPngAsDataUrlAndKeepsLink() {
        String svg = base64("<?xml version=\"1.0\"?>\n<!-- 메모 -->\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        byte[] png = Arrays.copyOf(PNG_SIGNATURE, PNG_SIGNATURE.length + 4);
        String pngData = Base64.getEncoder().encodeToString(png);

        ConnectorAppearance fromSvg = ConnectorAppearances.read(item(icon("image/svg+xml", svg), LINK));
        ConnectorAppearance fromPng = ConnectorAppearances.read(item(icon("image/png", pngData), null));

        assertThat(fromSvg).isEqualTo(new ConnectorAppearance("data:image/svg+xml;base64," + svg, LINK));
        assertThat(fromPng).isEqualTo(new ConnectorAppearance("data:image/png;base64," + pngData, null));
    }

    @DisplayName("문서 안을 가리키는 href 와 url() 참조는 받는다")
    @ParameterizedTest
    @ValueSource(
            strings = {
                "<a href=\"#a\"/>",
                "<use xlink:href='#a'/>",
                "<rect fill=\"url(#g)\"/>",
                "<rect fill=\"url('#g')\"/>",
                "<rect fill=\"url( #g)\"/>"
            })
    void acceptsInDocumentReferences(String body) {
        String data = base64("<svg xmlns=\"http://www.w3.org/2000/svg\">" + body + "</svg>");

        ConnectorAppearance read = ConnectorAppearances.read(item(icon("image/svg+xml", data), LINK));

        assertThat(read.icon()).isEqualTo("data:image/svg+xml;base64," + data);
    }

    @DisplayName("32 KiB 정확히인 아이콘은 받는다")
    @Test
    void acceptsIconOfExactlyMaxBytes() {
        String data = base64(paddedSvg(ICON_MAX_BYTES));

        ConnectorAppearance read = ConnectorAppearances.read(item(icon("image/svg+xml", data), LINK));

        assertThat(read.icon()).isEqualTo("data:image/svg+xml;base64," + data);
    }

    @DisplayName("금지 요소나 외부 참조가 있거나 svg 로 시작하지 않는 SVG 는 아이콘만 버리고 링크는 둔다")
    @ParameterizedTest
    @ValueSource(
            strings = {
                "<svg><script>alert(1)</script></svg>",
                "<svg onload=\"alert(1)\"/>",
                "<svg><foreignObject/></svg>",
                "<!DOCTYPE svg><svg/>",
                "<svg><set attributeName=\"x\" to=\"1\"/></svg>",
                "<svg><animate attributeName=\"x\"/></svg>",
                "<svg><text>&#106;</text></svg>",
                "<svg><style>a{b:\\61}</style></svg>",
                "<svg><a xlink:href=\"https://evil.example.test/\"/></svg>",
                "<svg><rect fill=\"url(http://evil.example.test/a)\"/></svg>",
                "<rect/>",
                "<svgx/>",
                "<svg"
            })
    void dropsUnsafeSvgButKeepsLink(String svg) {
        ConnectorAppearance read = ConnectorAppearances.read(item(icon("image/svg+xml", base64(svg)), LINK));

        assertThat(read).isEqualTo(new ConnectorAppearance(null, LINK));
    }

    @DisplayName("UTF-8 이 아닌 바이트, 틀린 base64, 빈 내용, 상한 초과, 서명 없는 PNG, 모르는 형식, 객체가 아닌 아이콘은 아이콘만 버린다")
    @Test
    void dropsMalformedIconButKeepsLink() {
        byte[] latin1 = "<svg>é</svg>".getBytes(StandardCharsets.ISO_8859_1);
        String png = Base64.getEncoder().encodeToString("not a png".getBytes(StandardCharsets.UTF_8));
        JsonNode[] icons = {
            icon("image/svg+xml", Base64.getEncoder().encodeToString(latin1)),
            icon("image/svg+xml", "%%%not-base64"),
            icon("image/svg+xml", ""),
            icon("image/svg+xml", base64(paddedSvg(ICON_MAX_BYTES + 1))),
            icon("image/png", png),
            icon("image/gif", base64("<svg/>")),
            JSON.getNodeFactory().stringNode("icon.svg"),
            JSON.getNodeFactory().objectNode().put("media_type", "image/svg+xml")
        };

        for (JsonNode declared : icons) {
            assertThat(ConnectorAppearances.read(item(declared, LINK)))
                    .as("icon=%s", declared)
                    .isEqualTo(new ConnectorAppearance(null, LINK));
        }
    }

    @DisplayName("http 링크, 사용자 정보가 있는 링크, 501자 링크, 읽히지 않는 주소, 글이 아닌 링크는 링크만 버린다")
    @Test
    void dropsInvalidLinkButKeepsIcon() {
        String data = base64("<svg/>");
        String tooLong = "https://notes.example.test/" + "a".repeat(501 - "https://notes.example.test/".length());
        JsonNode[] links = {
            text("http://notes.example.test/"),
            text("https://user@notes.example.test/"),
            text(tooLong),
            text("https://notes.example.test/a b"),
            text("https://[::1"),
            text("https:///path-only"),
            text(""),
            JSON.getNodeFactory().numberNode(7)
        };

        for (JsonNode declared : links) {
            ObjectNode item = item(icon("image/svg+xml", data), null);
            item.set("link", declared);
            assertThat(ConnectorAppearances.read(item))
                    .as("link=%s", declared)
                    .isEqualTo(new ConnectorAppearance("data:image/svg+xml;base64," + data, null));
        }
    }

    @DisplayName("500자 링크는 받는다")
    @Test
    void acceptsLinkOfExactlyMaxChars() {
        String link = "https://notes.example.test/" + "a".repeat(500 - "https://notes.example.test/".length());

        assertThat(ConnectorAppearances.read(item(null, link)).link()).isEqualTo(link);
    }

    @DisplayName("칸이 없거나 JSON null 이면 둘 다 null 이다")
    @Test
    void readsMissingOrNullFieldsAsNone() {
        ObjectNode missing = JSON.createObjectNode().put("id", "demo-notes");
        ObjectNode nulls =
                JSON.createObjectNode().put("id", "demo-notes").putNull("icon").putNull("link");

        assertThat(ConnectorAppearances.read(missing)).isEqualTo(ConnectorAppearance.NONE);
        assertThat(ConnectorAppearances.read(nulls)).isEqualTo(ConnectorAppearance.NONE);
    }

    @DisplayName("주석 2000개 뒤의 svg 루트는 받고 다른 글은 버리며, 둘 다 예외 없이 1초 안에 끝난다")
    @Test
    void scansManyLeadingCommentsWithoutBacktracking() {
        String comments = "<!---->".repeat(2000);
        String root = base64(comments + "<svg/>");
        String other = base64(comments + "X");

        ConnectorAppearance accepted = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> ConnectorAppearances.read(item(icon("image/svg+xml", root), null)));
        ConnectorAppearance rejected = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> ConnectorAppearances.read(item(icon("image/svg+xml", other), null)));

        assertThat(accepted.icon()).isEqualTo("data:image/svg+xml;base64," + root);
        assertThat(rejected).isEqualTo(ConnectorAppearance.NONE);
    }

    private static ObjectNode item(JsonNode icon, String link) {
        ObjectNode item = JSON.createObjectNode().put("id", "demo-notes");
        if (icon != null) {
            item.set("icon", icon);
        }
        if (link != null) {
            item.put("link", link);
        }
        return item;
    }

    private static ObjectNode icon(String mediaType, String data) {
        return JSON.createObjectNode().put("media_type", mediaType).put("data", data);
    }

    private static JsonNode text(String value) {
        return JSON.getNodeFactory().stringNode(value);
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 앞뒤 태그 사이를 공백으로 채워 UTF-8 로 정확히 {@code bytes} 바이트인 SVG 를 만든다. */
    private static String paddedSvg(int bytes) {
        String open = "<svg xmlns=\"http://www.w3.org/2000/svg\">";
        String close = "</svg>";
        return open + " ".repeat(bytes - open.length() - close.length()) + close;
    }
}
