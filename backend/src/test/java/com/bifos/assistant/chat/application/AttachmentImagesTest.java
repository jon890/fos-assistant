package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    @DisplayName("가장 작은 단계에서 차례로 낮추는 JPEG 품질은 0.85, 0.6, 0.4, 0.3 이다")
    void smallestStepQualitiesDescendFromDefault() {
        assertThat(AttachmentImages.SMALLEST_QUALITIES).containsExactly(0.85f, 0.6f, 0.4f, 0.3f);
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

    @Test
    @DisplayName("앞 사진이 바이트 상한을 넘겨 담지 못하면 뒤의 더 작은 주소도 담지 않는다")
    void admitInOrderStopsAtFirstPhotoOverEncodedLimit() {
        List<Dimension> sizes = List.of(new Dimension(768, 576), new Dimension(768, 576), new Dimension(8, 6));
        String half = "a".repeat((int) (4 * MB));
        List<String> encoded = List.of(half, half, "small");

        List<String> admitted = AttachmentImages.admitInOrder(sizes, encoded, 768);

        assertThat(admitted).as("둘째에서 7MB 를 넘으면 셋째도 담지 않는다").containsExactly(half, null, null);
    }

    @Test
    @DisplayName("주소가 없는 사진은 건너뛰고 뒤 사진을 담는다")
    void admitInOrderSkipsPhotoWithoutDataUrl() {
        List<Dimension> sizes = List.of(new Dimension(768, 576), new Dimension(8, 6));
        List<String> encoded = Arrays.asList(null, "small");

        List<String> admitted = AttachmentImages.admitInOrder(sizes, encoded, 768);

        assertThat(admitted).containsExactly(null, "small");
    }

    @Test
    @DisplayName("보낼 때의 대기는 한 장 5초와 마감까지 남은 시간 가운데 짧은 쪽이고 마감이 지나면 0 이다")
    void sendWaitIsShorterOfPerPhotoWaitAndTimeLeft() {
        long perPhoto = TimeUnit.SECONDS.toNanos(AttachmentImages.SEND_WAIT_SECONDS);
        long deadline = 1_000_000_000_000L;

        assertThat(AttachmentImages.SEND_DEADLINE_SECONDS).isEqualTo(15);
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline - TimeUnit.SECONDS.toNanos(15)))
                .as("마감이 15초 남으면 한 장 상한 5초")
                .isEqualTo(perPhoto);
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline - TimeUnit.SECONDS.toNanos(2)))
                .as("마감이 2초 남으면 2초")
                .isEqualTo(TimeUnit.SECONDS.toNanos(2));
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline)).as("마감 시각이면 0").isZero();
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline + 1)).as("마감이 지나면 0").isZero();
    }
}
