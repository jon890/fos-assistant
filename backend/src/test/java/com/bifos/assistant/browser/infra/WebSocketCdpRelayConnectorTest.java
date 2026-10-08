package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.domain.CdpRelay;
import com.bifos.assistant.browser.domain.CdpRelayListener;
import com.bifos.assistant.browser.infra.WebSocketCdpConnectorTest.FakeCdpServer;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebSocketCdpRelayConnectorTest {

    private static final String BROWSER_ID = "0a1b2c3d-1111-2222-3333-444455556666";

    private FakeCdpServer server;
    private WebSocketCdpRelayConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeCdpServer();
        connector = new WebSocketCdpRelayConnector();
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.close();
        server.close();
    }

    @Test
    @DisplayName("GUID 모양 번호의 브라우저 대상에 Origin 없이 붙고, 보낸 조각이 한 메시지로 이어져 닿는다")
    void opensBrowserTargetAndSendsFragments() throws Exception {
        CdpRelay relay = open("browser", BROWSER_ID, new Recording());

        relay.send("{\"id\":1,", false);
        relay.send("\"method\":\"Target.getTargets\"}", true);

        assertThat(server.requestLine()).isEqualTo("GET /devtools/browser/" + BROWSER_ID + " HTTP/1.1");
        assertThat(server.headers()).noneMatch(line -> line.toLowerCase().startsWith("origin:"));
        assertThat(server.nextMessage().path("method").asString()).isEqualTo("Target.getTargets");
        relay.close();
    }

    @Test
    @DisplayName("조각 둘로 온 메시지는 모으지 않고 차례대로 넘기며 마지막 조각만 last 다")
    void deliversFragmentsInOrder() throws Exception {
        Recording listener = new Recording();
        CdpRelay relay = open("page", "PAGE1", listener);
        String message = "{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"" + "a".repeat(20_000) + "\"}}";

        server.sendFragmented(message, 2);

        StringBuilder joined = new StringBuilder();
        int count = 0;
        while (true) {
            Fragment fragment = listener.fragments.poll(2, TimeUnit.SECONDS);
            assertThat(fragment).as("%d 번째 조각", count + 1).isNotNull();
            joined.append(fragment.text());
            count++;
            if (fragment.last()) {
                break;
            }
        }
        assertThat(count).as("넘긴 조각 수").isGreaterThanOrEqualTo(2);
        assertThat(joined.toString()).isEqualTo(message);
        assertThat(listener.fragments.poll(200, TimeUnit.MILLISECONDS)).isNull();
        relay.close();
    }

    @Test
    @DisplayName("서버가 연결을 닫으면 닫힘을 한 번 알리고, 그 뒤의 보내기는 실패한다")
    void notifiesOnceWhenServerCloses() throws Exception {
        Recording listener = new Recording();
        CdpRelay relay = open("page", "PAGE1", listener);

        server.dropConnection();

        assertThat(listener.closedOnce.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> relay.send("{}", true)).isInstanceOf(IllegalStateException.class);
        relay.close();
        Thread.sleep(200);
        assertThat(listener.closed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("close 로 닫으면 연결을 끊고 닫힘은 알리지 않는다")
    void closeDoesNotNotify() throws Exception {
        Recording listener = new Recording();
        CdpRelay relay = open("page", "PAGE1", listener);

        relay.close();

        assertThat(server.awaitDisconnected()).isTrue();
        assertThat(listener.closedOnce.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    @DisplayName("번호에 / 가 있거나 128자를 넘거나, 종류가 page 와 browser 가 아니면 붙지 않고 거절한다")
    void rejectsMalformedTarget() {
        URI cdp = server.address();
        for (String id : new String[] {"../browser", "a/b", "", "a".repeat(129), null}) {
            assertThatThrownBy(() -> connector.open(cdp, "page", id, new Recording()))
                    .as("번호 %s", id)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> connector.open(cdp, "worker", "PAGE1", new Recording()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("128자 번호는 받는다")
    void acceptsLongestTargetId() throws Exception {
        String id = "a".repeat(128);

        CdpRelay relay = open("page", id, new Recording());

        assertThat(server.requestLine()).isEqualTo("GET /devtools/page/" + id + " HTTP/1.1");
        relay.close();
    }

    private CdpRelay open(String kind, String id, CdpRelayListener listener) throws Exception {
        CompletableFuture<Void> accepted = server.acceptAsync();
        CdpRelay relay = connector.open(server.address(), kind, id, listener);
        accepted.get(2, TimeUnit.SECONDS);
        return relay;
    }

    private record Fragment(String text, boolean last) {}

    /** 받은 조각과 닫힘 알림 수를 모은다. */
    private static final class Recording implements CdpRelayListener {

        final BlockingQueue<Fragment> fragments = new LinkedBlockingQueue<>();
        final AtomicInteger closed = new AtomicInteger();
        final CountDownLatch closedOnce = new CountDownLatch(1);

        @Override
        public void onFragment(String fragment, boolean last) {
            fragments.add(new Fragment(fragment, last));
        }

        @Override
        public void onClosed() {
            closed.incrementAndGet();
            closedOnce.countDown();
        }
    }
}
