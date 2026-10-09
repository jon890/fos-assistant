package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.shared.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentInspectionQueueTest {
    @Test
    @DisplayName("병렬 원본 조회는 디코딩 차례를 기다리고 취소된 조회는 파일을 열지 않는다")
    void parallelReadsWaitForDecodeSlotAndCancellationDoesNotFetch() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] original = encoded.toByteArray();
        AttachmentStore store = mock(AttachmentStore.class);
        ChatAttachment photo = mock(ChatAttachment.class);
        when(photo.contentType()).thenReturn("image/png");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        when(store.open(photo)).thenAnswer(invocation -> {
            if (opened.getAndIncrement() != 0) {
                return new ByteArrayInputStream(original);
            }
            return new ByteArrayInputStream(original) {
                @Override
                public synchronized byte[] readNBytes(int length) throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test wait expired");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return super.readNBytes(length);
                }
            };
        });
        AttachmentInspection inspection = new AttachmentInspection(store);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> inspection.read(photo, null));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> inspection.read(photo, null));
            assertThat(opened.get()).isEqualTo(1);
            assertThatThrownBy(() -> inspection.read(photo, null, () -> false)).isInstanceOf(ApiException.class);
            assertThat(opened.get()).isEqualTo(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).bytes()).isEqualTo(original);
            assertThat(second.get(5, TimeUnit.SECONDS).bytes()).isEqualTo(original);
        } finally {
            release.countDown();
        }
    }
}
