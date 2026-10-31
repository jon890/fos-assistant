package com.bifos.assistant.chat.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HTML 과 CSS 가 불러오는 주소만 찾고, 묶음에 넣지 못하는 주소는 거절하는지 확인한다(#530 PR 2). */
class ArtifactReferencesTest {

    @Test
    @DisplayName("미리보기가 불러오는 자리만 보고 링크와 스크립트, 주석, 문서 안 주소, 미리보기에 쓰이지 않는 link 는 보지 않는다")
    void findsOnlyLoadingReferences() {
        String html = """
            <!-- <img src="commented.png"> -->
            <IMG SRC='a.png' srcset="b.png 2x, c.png 480w">
            <a href="https://example.test/">링크</a>
            <link rel="icon" href="icon.png"><link rel="alternate" href="https://example.test/feed">
            <script>var x = "<img src='in-script.png'>";</script>
            <div style="background-image: url(&quot;d.png&quot;)"></div>
            <video poster="e.gif"></video><use xlink:href="#x"/>
            <title><img src="in-title.png"></title>
            <img src="a.png?x=1#frag">
            <noscript><img src="f.png"></noscript>
            <link rel="modulepreload" href="app.js"><link rel="manifest" href="site.webmanifest">
            <link rel="preload" href="font.woff2" as="font"><link rel="preload" as="image" href="g.webp">
            """;

        assertThat(ArtifactReferences.ofHtml("site/index.html", html)).containsExactly(
            "site/a.png", "site/b.png", "site/c.png", "site/icon.png", "site/d.png", "site/e.gif", "site/f.png",
            "site/g.webp");
    }


    @Test
    @DisplayName("CSS 의 url 과 import, image-set 을 그 파일의 폴더 기준으로 푼다")
    void resolvesCssRelativeToItsFolder() {
        String css = """
            @import 'base.css';
            @import url("../shared/theme.css");
            /* url(skip.png) */
            .a { background: URL( img/one.png ) }
            .b { background-image: image-set("two.png" 1x, 'three.png' 2x) }
            .c { content: "url(not-a-url.png)" }
            .d { background: -webkit-image-set(url(four.png) 1x) }
            """;

        assertThat(ArtifactReferences.ofCss("site/css/app.css", css)).containsExactlyInAnyOrder(
            "site/css/base.css", "site/shared/theme.css", "site/css/img/one.png", "site/css/two.png",
            "site/css/three.png", "site/css/four.png");
    }


    @Test
    @DisplayName("퍼센트 인코딩과 점 조각을 풀어 대화 폴더 안의 경로로 만든다")
    void normalizesPaths() {
        assertThat(ArtifactReferences.resolve("a/b/page.html", "./img/../img/%ED%95%9C+1.png"))
            .isEqualTo("a/b/img/한+1.png");
        assertThat(ArtifactReferences.resolve("page.html", "#top")).isNull();
        assertThat(ArtifactReferences.resolve("page.html", "  ")).isNull();
        assertThat(ArtifactReferences.normalize("a/./b//c.html")).isEqualTo("a/b/c.html");
        assertThat(ArtifactReferences.normalize("/a.html")).isNull();
    }


    @Test
    @DisplayName("외부 주소와 data 주소, 절대 경로, 폴더 위로 가는 주소는 거절한다")
    void rejectsAddressesOutsideTheBundle() {
        for (String reference : new String[] {
            "https://example.test/a.png", "//example.test/a.png", "data:image/png;base64,AAAA", "/a.png",
            "../a.png", "javascript:alert(1)", "img/%2e%2e/%2e%2e/../a.png", "a%5Cb.png"}) {
            assertThatThrownBy(() -> ArtifactReferences.resolve("page.html", reference))
                .as(reference)
                .isInstanceOf(ArtifactBundleRejectedException.class);
        }
        assertThatThrownBy(() -> ArtifactReferences.ofHtml("p.html", "<img src=\"&#x68;ttps://example.test/a.png\">"))
            .isInstanceOf(ArtifactBundleRejectedException.class);
    }
}
