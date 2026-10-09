package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 한 턴에 사진을 어느 단계로 싣고 더 실을 수 있는지의 판정을 본다. */
class AttachmentImagesTest {

    private static final long MB = 1024L * 1024;
    private static final long MAX_PIXELS = 19_200_000L;

    @Test
    @DisplayName("한 턴 예산은 화소 1,920만과 7MB 이고 단계는 1600, 1280, 1024, 768 이다")
    void defaultBudgetIsPixelsAndSevenMegabytesWithFourLongSides() {
        assertThat(AttachmentImages.MAX_PIXELS).isEqualTo(19_200_000L);
        assertThat(AttachmentImages.MAX_ENCODED_BYTES).isEqualTo(7 * MB);
        assertThat(AttachmentImages.LONG_SIDES).containsExactly(1600, 1280, 1024, 768);
    }

    @Test
    @DisplayName("4:3 사진 열 장은 1600 단계에 든다")
    void tenLandscapePhotosFitAtFullSize() {
        assertThat(AttachmentImages.chooseLongSide(photos(10, 1600, 1200), MAX_PIXELS))
                .isEqualTo(1600);
    }

    @Test
    @DisplayName("4:3 사진 열한 장은 1280, 열여섯 장은 1024, 서른 장은 768 단계를 고른다")
    void morePhotosChooseSmallerLongSide() {
        assertThat(AttachmentImages.chooseLongSide(photos(11, 1600, 1200), MAX_PIXELS))
                .isEqualTo(1280);
        assertThat(AttachmentImages.chooseLongSide(photos(16, 1600, 1200), MAX_PIXELS))
                .isEqualTo(1024);
        assertThat(AttachmentImages.chooseLongSide(photos(30, 1600, 1200), MAX_PIXELS))
                .isEqualTo(768);
    }

    @Test
    @DisplayName("세로 사진 열한 장도 가로 사진과 같은 1280 단계를 고른다")
    void portraitPhotosChooseSameLongSideAsLandscape() {
        assertThat(AttachmentImages.chooseLongSide(photos(11, 1200, 1600), MAX_PIXELS))
                .isEqualTo(1280);
    }

    @Test
    @DisplayName("768 단계에서도 예산을 넘으면 768 단계이고 사진이 없으면 1600 단계다")
    void overflowAtSmallestChoosesSmallestAndEmptyChoosesLargest() {
        assertThat(AttachmentImages.chooseLongSide(photos(45, 1600, 1200), MAX_PIXELS))
                .isEqualTo(768);
        assertThat(AttachmentImages.chooseLongSide(List.of(), MAX_PIXELS)).isEqualTo(1600);
    }

    @Test
    @DisplayName("화소 합이 상한을 넘거나 길이 합이 상한을 넘으면 거짓이고 둘 다 상한과 같으면 참이다")
    void admitsOnlyWhenBothSumsStayWithinLimits() {
        assertThat(AttachmentImages.admits(19_000_000L, 0, 200_001L, 1, MAX_PIXELS, 7 * MB))
                .isFalse();
        assertThat(AttachmentImages.admits(0, 6 * MB, 1, 1 * MB + 1, MAX_PIXELS, 7 * MB))
                .isFalse();
        assertThat(AttachmentImages.admits(19_000_000L, 6 * MB, 200_000L, 1 * MB, MAX_PIXELS, 7 * MB))
                .isTrue();
    }

    private static List<Dimension> photos(int count, int width, int height) {
        return Collections.nCopies(count, new Dimension(width, height));
    }
}
