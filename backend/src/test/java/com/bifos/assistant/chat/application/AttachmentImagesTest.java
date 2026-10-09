package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 한 턴에 사진을 더 실을 수 있는지의 판정을 본다. */
class AttachmentImagesTest {

    private static final long MB = 1024L * 1024;

    @Test
    @DisplayName("이미 상한 장수를 담았으면 작은 사진도 더 담지 않는다")
    void rejectsWhenImageCountReachedLimit() {
        assertThat(AttachmentImages.admits(3, 0, 1, 3, 7 * MB)).isFalse();
        assertThat(AttachmentImages.admits(2, 0, 1, 3, 7 * MB)).isTrue();
    }

    @Test
    @DisplayName("담은 길이와 다음 사진의 합이 상한을 넘으면 거짓이고 상한과 같으면 참이다")
    void rejectsWhenEncodedBytesExceedLimitAndAdmitsAtLimit() {
        assertThat(AttachmentImages.admits(1, 6 * MB, 2 * MB, 10, 7 * MB)).isFalse();
        assertThat(AttachmentImages.admits(1, 6 * MB, 1 * MB, 10, 7 * MB)).isTrue();
    }

    @Test
    @DisplayName("한 턴 상한은 10장과 7MB 다")
    void defaultLimitsAreTenImagesAndSevenMegabytes() {
        assertThat(AttachmentImages.MAX_IMAGES).isEqualTo(10);
        assertThat(AttachmentImages.MAX_ENCODED_BYTES).isEqualTo(7 * MB);
        assertThat(AttachmentImages.admits(9, 0, 1, AttachmentImages.MAX_IMAGES, AttachmentImages.MAX_ENCODED_BYTES))
                .isTrue();
        assertThat(AttachmentImages.admits(10, 0, 1, AttachmentImages.MAX_IMAGES, AttachmentImages.MAX_ENCODED_BYTES))
                .isFalse();
        assertThat(AttachmentImages.admits(
                        0, 0, 7 * MB + 1, AttachmentImages.MAX_IMAGES, AttachmentImages.MAX_ENCODED_BYTES))
                .isFalse();
    }
}
