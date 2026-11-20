package com.bifos.assistant.testsupport;

import com.bifos.assistant.notification.infra.PushResponse;
import com.bifos.assistant.notification.infra.WebPushClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 네트워크 없이 웹 푸시 요청을 모으는 대역이다. 검사가 정하지 않으면 201 을 돌려준다.
 *
 * <p>검사는 돌려줄 상태 코드의 차례와, 보내기를 붙잡아 둘 문({@link #holdUntil})을 정한다. 붙잡은 문은 검사 안에서 반드시 연다.
 * {@link IntegrationTestIsolation} 이 검사마다 {@link #reset()} 을 부른다.
 */
public class RecordingWebPushClient implements WebPushClient {

    private final List<Sent> sent = new ArrayList<>();
    private final Deque<PushResponse> responses = new ArrayDeque<>();
    private CountDownLatch gate;


    /** 받은 요청 하나다. */
    public record Sent(String endpoint, String authorization, byte[] body) {
    }


    @Override
    public PushResponse send(String endpoint, String authorization, byte[] body) {
        CountDownLatch waitFor;
        synchronized (this) {
            sent.add(new Sent(endpoint, authorization, body));
            waitFor = gate;
        }
        if (waitFor != null) {
            try {
                waitFor.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            return responses.isEmpty() ? new PushResponse(201, null) : responses.poll();
        }
    }


    /** 다음 요청들이 차례대로 받을 상태 코드다. 다 쓰면 201 이다. */
    public synchronized void respond(int... statuses) {
        for (int status : statuses) {
            responses.add(new PushResponse(status, null));
        }
    }


    /** 다음 요청이 받을 응답을 기다림과 함께 정한다. */
    public synchronized void respond(int status, Duration retryAfter) {
        responses.add(new PushResponse(status, retryAfter));
    }


    /** 문을 열 때까지 보내기를 붙잡는다. */
    public synchronized void holdUntil(CountDownLatch open) {
        this.gate = open;
    }


    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }


    public synchronized void reset() {
        sent.clear();
        responses.clear();
        gate = null;
    }
}
