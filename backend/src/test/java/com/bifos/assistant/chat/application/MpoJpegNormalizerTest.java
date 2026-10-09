package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MpoJpegNormalizerTest {

    @Test
    @DisplayName("여러 SOS 구간이 있는 progressive MPO도 첫 JPEG를 그대로 남긴다")
    void preservesProgressiveJpeg() throws IOException {
        assertThat(MpoJpegNormalizer.normalize(fixture("progressive.mpo"))).isEqualTo(fixture("progressive.jpg"));
    }

    @Test
    @DisplayName("메타데이터 안의 EOI 바이트를 끝으로 오인하지 않고 다른 APP2를 보존한다")
    void preservesOtherAppSegmentsContainingEoi() throws IOException {
        byte[] segment = {(byte) 0xff, (byte) 0xe2, 0, 6, 'I', 'C', (byte) 0xff, (byte) 0xd9};
        assertThat(MpoJpegNormalizer.normalize(insertSegment(fixture("synthetic.mpo"), segment)))
                .isEqualTo(insertSegment(fixture("first.jpg"), segment));
    }

    private static byte[] insertSegment(byte[] image, byte[] segment) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(image, 0, 2);
        output.writeBytes(segment);
        output.write(image, 2, image.length - 2);
        return output.toByteArray();
    }

    @Test
    @DisplayName("MPO의 첫 JPEG 바이트와 EXIF를 그대로 남기고 MPF와 보조 사진을 제거한다")
    void preservesFirstJpegWithoutReencoding() throws IOException {
        assertThat(MpoJpegNormalizer.normalize(fixture("synthetic.mpo"))).isEqualTo(fixture("first.jpg"));
    }

    @Test
    @DisplayName("일반 JPEG는 원본 배열을 그대로 돌려준다")
    void preservesOrdinaryJpeg() throws IOException {
        byte[] original = fixture("first.jpg");
        assertThat(MpoJpegNormalizer.normalize(original)).isSameAs(original);
    }

    @Test
    @DisplayName("EOI가 없는 MPO와 잘못된 구간 길이는 원본을 유지한다")
    void preservesMalformedInput() throws IOException {
        byte[] mpo = fixture("synthetic.mpo");
        byte[] truncated = Arrays.copyOf(mpo, 200);
        assertThat(MpoJpegNormalizer.normalize(truncated)).isSameAs(truncated);
        byte[] invalid = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe2, 0, 1};
        assertThat(MpoJpegNormalizer.normalize(invalid)).isSameAs(invalid);
    }

    @Test
    @DisplayName("MPF와 SOI EOI만 있고 디코딩할 수 없는 JPEG는 원본을 유지한다")
    void preservesUndecodableFirstImage() {
        byte[] invalid = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe2, 0, 6,
            'M', 'P', 'F', 0, (byte) 0xff, (byte) 0xd9
        };
        assertThat(MpoJpegNormalizer.normalize(invalid)).isSameAs(invalid);
    }

    private static byte[] fixture(String name) throws IOException {
        try (var input = MpoJpegNormalizerTest.class.getResourceAsStream("/attachments/" + name)) {
            return input.readAllBytes();
        }
    }
}
