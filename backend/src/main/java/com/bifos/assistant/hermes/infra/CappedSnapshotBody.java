package com.bifos.assistant.hermes.infra;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** chunked 응답도 JSON tree와 무제한 byte 배열 이전에 차단한다. */
final class CappedSnapshotBody implements BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;


    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }


    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }


    @Override
    public void onNext(List<ByteBuffer> chunks) {
        for (var chunk : chunks) {
            if (chunk.remaining() > 524288 - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new IllegalStateException("snapshot 응답 상한 초과"));
                return;
            }
            byte[] part = new byte[chunk.remaining()];
            chunk.get(part);
            bytes.writeBytes(part);
        }
        subscription.request(1);
    }


    @Override
    public void onError(Throwable ignored) {
        body.completeExceptionally(new IllegalStateException("snapshot 응답 중단"));
    }


    @Override
    public void onComplete() {
        body.complete(bytes.toByteArray());
    }
}
