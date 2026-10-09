package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.workspace.application.model.WorkspacePreview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspacePreviewPolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    @DisplayName("HTML 은 html 미리보기이고 5 MiB 까지다")
    void previewsHtml() {
        WorkspacePreview preview = WorkspacePreviewPolicy.of("a.html").orElseThrow();

        assertThat(preview.html()).isTrue();
        assertThat(preview.contentType()).isEqualTo("text/html; charset=utf-8");
        assertThat(preview.maxBytes()).isEqualTo(5 * MIB);
    }

    @Test
    @DisplayName("CSS 는 text/css 로 준다")
    void previewsCssAsCss() {
        assertThat(WorkspacePreviewPolicy.of("a.css").orElseThrow().contentType())
                .isEqualTo("text/css; charset=utf-8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.TXT", ".env", "Makefile", "a.svg", "a.csv"})
    @DisplayName("글 확장자, 점으로 시작하는 env, 확장자가 없는 이름은 글이다")
    void previewsTextNames(String name) {
        WorkspacePreview preview = WorkspacePreviewPolicy.of(name).orElseThrow();

        assertThat(preview.contentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(preview.maxBytes()).isEqualTo(MIB);
        assertThat(preview.html()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {".gitignore", "a.bin", "a."})
    @DisplayName("정하지 않은 확장자는 미리보기가 없다")
    void hasNoPreviewForUnknownExtensions(String name) {
        assertThat(WorkspacePreviewPolicy.of(name)).isEmpty();
    }

    @Test
    @DisplayName("PNG 는 사진 형식이고 20 MiB 까지다")
    void previewsPng() {
        WorkspacePreview preview = WorkspacePreviewPolicy.of("a.png").orElseThrow();

        assertThat(preview.contentType()).isEqualTo("image/png");
        assertThat(preview.maxBytes()).isEqualTo(20 * MIB);
    }
}
