package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 한 턴에 사진을 어느 단계로 싣고 더 실을 수 있는지의 판정을 본다. */
class AttachmentImagesTest {

    private static final long MB = 1024L * 1024;

    @Test
    @DisplayName("사진의 바이트 예산은 7MiB 이고 큰 해상도부터 시도한다")
    void encodedBudgetKeepsHermesRequestHeadroom() {
        assertThat(AttachmentImages.MAX_ENCODED_BYTES).isEqualTo(7 * MB);
        assertThat(AttachmentImages.LONG_SIDES).containsExactly(1600, 1280, 1024, 768);
    }

    @Test
    @DisplayName("가장 작은 단계에서 차례로 낮추는 JPEG 품질은 0.85, 0.6, 0.4, 0.3 이다")
    void smallestStepQualitiesDescendFromDefault() {
        assertThat(AttachmentImages.SMALLEST_QUALITIES).containsExactly(0.85f, 0.6f, 0.4f, 0.3f);
    }

    @Test
    @DisplayName("앞 사진이 바이트 상한을 넘겨 담지 못하면 뒤의 더 작은 주소도 담지 않는다")
    void admitInOrderStopsAtFirstPhotoOverEncodedLimit() {
        String half = "a".repeat((int) (4 * MB));
        List<String> encoded = List.of(half, half, "small");

        List<String> admitted = AttachmentImages.admitInOrder(encoded);

        assertThat(admitted).as("둘째에서 7MB 를 넘으면 셋째도 담지 않는다").containsExactly(half, null, null);
    }

    @Test
    @DisplayName("주소가 없는 사진은 건너뛰고 뒤 사진을 담는다")
    void admitInOrderSkipsPhotoWithoutDataUrl() {
        List<String> encoded = Arrays.asList(null, "small");

        List<String> admitted = AttachmentImages.admitInOrder(encoded);

        assertThat(admitted).containsExactly(null, "small");
    }

    @Test
    @DisplayName("바이트 합이 예산과 같으면 모든 사진을 순서대로 남긴다")
    void admitsExactByteBudgetInOrder() {
        String first = "a".repeat((int) (3 * MB));
        String second = "b".repeat((int) (4 * MB));

        assertThat(AttachmentImages.admitInOrder(List.of(first, second))).containsExactly(first, second);
        assertThat(AttachmentImages.admitInOrder(List.of())).isEmpty();
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
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline))
                .as("마감 시각이면 0")
                .isZero();
        assertThat(AttachmentImages.sendWaitNanos(deadline, deadline + 1))
                .as("마감이 지나면 0")
                .isZero();
    }
}
