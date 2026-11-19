package com.bifos.assistant.chat.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.artifact.domain.VaultFileType.Kind;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 「내 파일」 이 받는 형식을 확장자와 머리 바이트로 정하는지 확인한다(스펙 workspace REQ4). */
class VaultFileTypeTest {

    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0};
    private static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);


    @Test
    @DisplayName("확장자로 그림, 문서, 표를 나누고 대소문자를 구분하지 않는다")
    void kindsByExtension() {
        assertThat(VaultFileType.of("a/고양이.PNG")).get().extracting(VaultFileType::kind).isEqualTo(Kind.IMAGE);
        assertThat(VaultFileType.of("보고서.pdf")).get().extracting(VaultFileType::kind).isEqualTo(Kind.DOCUMENT);
        assertThat(VaultFileType.of("index.html")).get().extracting(VaultFileType::kind).isEqualTo(Kind.DOCUMENT);
        assertThat(VaultFileType.of("발표.pptx")).get().extracting(VaultFileType::kind).isEqualTo(Kind.DOCUMENT);
        assertThat(VaultFileType.of("가계부.xlsx")).get().extracting(VaultFileType::kind).isEqualTo(Kind.TABLE);
        assertThat(VaultFileType.of("data.tsv")).get().extracting(VaultFileType::kind).isEqualTo(Kind.TABLE);
    }


    @Test
    @DisplayName("압축 파일과 스크립트, 확장자가 없는 이름은 받지 않는다")
    void rejectsOtherExtensions() {
        assertThat(VaultFileType.of("a.zip")).isEmpty();
        assertThat(VaultFileType.of("run.sh")).isEmpty();
        assertThat(VaultFileType.of("a.svg")).isEmpty();
        assertThat(VaultFileType.of("Makefile")).isEmpty();
        assertThat(VaultFileType.of("dir.xlsx/readme")).isEmpty();
        assertThat(VaultFileType.of(null)).isEmpty();
    }


    @Test
    @DisplayName("새 형식은 화면에서 그대로 열지 않고 내려받기로만 준다")
    void newKindsAreDownloadOnly() {
        assertThat(VaultFileType.of("a.xlsx").orElseThrow().inline()).isFalse();
        assertThat(VaultFileType.of("a.pdf").orElseThrow().contentType()).isEqualTo("application/octet-stream");
        assertThat(VaultFileType.of("a.html").orElseThrow().inline()).isTrue();
        assertThat(VaultFileType.of("a.png").orElseThrow().contentType()).isEqualTo("image/png");
    }


    @Test
    @DisplayName("이진 형식은 머리 바이트가 맞아야 하고 글 형식은 NUL 이 없어야 한다")
    void matchesMagicBytes() {
        assertThat(VaultFileType.of("a.xlsx").orElseThrow().matches(ZIP)).isTrue();
        assertThat(VaultFileType.of("a.xlsx").orElseThrow().matches(PDF)).isFalse();
        assertThat(VaultFileType.of("a.pdf").orElseThrow().matches(PDF)).isTrue();
        assertThat(VaultFileType.of("a.pdf").orElseThrow().matches(ZIP)).isFalse();
        assertThat(VaultFileType.of("a.csv").orElseThrow().matches("이름,값\n가,1\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(VaultFileType.of("a.csv").orElseThrow().matches(new byte[] {'a', 0, 'b'})).isFalse();
        assertThat(VaultFileType.of("a.png").orElseThrow().matches(ZIP)).isFalse();
    }
}
