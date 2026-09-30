package com.bifos.assistant.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 DNS와 인터넷 없이 TLS 연결의 대상 IP, SNI, Host, SAN 검증을 확인한다. */
class ArtifactSourceTlsTest {

    private static final char[] PASSWORD = "test-password".toCharArray();
    private static final String ORIGINAL_HOST = "images.artifact-test.invalid";

    @Test
    @DisplayName("전달한 IP로 연결하고 원래 호스트의 SNI와 Host와 SAN을 쓴다")
    void connectsToGivenIpWithOriginalHostSniHostAndSan(@TempDir Path directory) throws Exception {
        TlsMaterial material = tlsMaterial(directory, ORIGINAL_HOST);
        try (TlsServer server = new TlsServer(material.serverContext())) {
            ArtifactSourceFetcher.SocketTransport transport = new ArtifactSourceFetcher.SocketTransport(
                    material.clientFactory(), server.port());

            try (ArtifactSourceFetcher.Response response = transport.get(InetAddress.getByName("127.0.0.1"), ORIGINAL_HOST,
                    URI.create("https://" + ORIGINAL_HOST + "/image.png"), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), new ArtifactSourceFetcher.Cancellation())) {
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body().readAllBytes()).containsExactly('o', 'k');
            }

            server.await();
            assertThat(server.requestedSni()).isEqualTo(ORIGINAL_HOST);
            assertThat(server.hostHeader()).isEqualTo(ORIGINAL_HOST);
            assertThat(server.failure()).isNull();
        }
    }

    @Test
    @DisplayName("원래 호스트와 다른 SAN은 신뢰한 인증서여도 HTTPS 식별에서 거절한다")
    void rejectsSanDifferentFromOriginalHostInHttpsIdentification(@TempDir Path directory)
            throws Exception {
        TlsMaterial material = tlsMaterial(directory, "other.artifact-test.invalid");
        try (TlsServer server = new TlsServer(material.serverContext())) {
            ArtifactSourceFetcher.SocketTransport transport = new ArtifactSourceFetcher.SocketTransport(
                    material.clientFactory(), server.port());

            assertThatThrownBy(() -> transport.get(InetAddress.getByName("127.0.0.1"), ORIGINAL_HOST,
                    URI.create("https://" + ORIGINAL_HOST + "/image.png"), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), new ArtifactSourceFetcher.Cancellation()))
                    .isInstanceOf(IOException.class);

            server.await();
        }
    }

    @Test
    @DisplayName("TLS 응답 머리글이 늦으면 읽기 제한으로 실패한다")
    void failsWithReadLimitWhenTlsResponseHeadersAreLate(@TempDir Path directory) throws Exception {
        TlsMaterial material = tlsMaterial(directory, ORIGINAL_HOST);
        try (TlsServer server = new TlsServer(material.serverContext(), Duration.ofMillis(1200), false)) {
            ArtifactSourceFetcher.SocketTransport transport = new ArtifactSourceFetcher.SocketTransport(material.clientFactory(), server.port());
            assertThatThrownBy(() -> transport.get(InetAddress.getByName("127.0.0.1"), ORIGINAL_HOST,
                    URI.create("https://" + ORIGINAL_HOST + "/image.png"), Duration.ofSeconds(1),
                    Duration.ofMillis(300), new ArtifactSourceFetcher.Cancellation())).isInstanceOf(IOException.class);
            server.await();
            assertThat(server.hostHeader()).isEqualTo(ORIGINAL_HOST);
        }
    }

    @Test
    @DisplayName("TLS 본문이 조금씩 오면 각 읽기에 제한을 적용한다")
    void appliesLimitToEachReadWhenTlsBodyArrivesInDribbles(@TempDir Path directory) throws Exception {
        TlsMaterial material = tlsMaterial(directory, ORIGINAL_HOST);
        try (TlsServer server = new TlsServer(material.serverContext(), Duration.ofMillis(1200), true)) {
            ArtifactSourceFetcher.SocketTransport transport = new ArtifactSourceFetcher.SocketTransport(material.clientFactory(), server.port());
            try (ArtifactSourceFetcher.Response response = transport.get(InetAddress.getByName("127.0.0.1"), ORIGINAL_HOST,
                    URI.create("https://" + ORIGINAL_HOST + "/image.png"), Duration.ofSeconds(1),
                    Duration.ofMillis(300), new ArtifactSourceFetcher.Cancellation())) {
                assertThatThrownBy(() -> response.body().readAllBytes()).isInstanceOf(IOException.class);
            }
        }
    }

    private static TlsMaterial tlsMaterial(Path directory, String subjectAlternativeName) throws Exception {
        Path keyStore = directory.resolve("server.p12");
        Process process = new ProcessBuilder(keytool(), "-genkeypair", "-alias", "server", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", String.valueOf(PASSWORD), "-keypass",
                String.valueOf(PASSWORD), "-dname", "CN=" + subjectAlternativeName, "-ext",
                "SAN=dns:" + subjectAlternativeName, "-keyalg", "RSA", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (process.waitFor() != 0) {
            throw new IOException("could not create local TLS certificate: " + output);
        }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStore)) {
            store.load(input, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(keys.getKeyManagers(), null, null);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trust.getTrustManagers(), null);
        return new TlsMaterial(server, client.getSocketFactory());
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }

    private record TlsMaterial(SSLContext serverContext, SSLSocketFactory clientFactory) {
    }

    private static final class TlsServer implements AutoCloseable {
        private final SSLServerSocket socket;
        private final Thread worker;
        private final AtomicReference<String> requestedSni = new AtomicReference<>();
        private final AtomicReference<String> hostHeader = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Duration delayedBody;
        private final boolean splitBody;

        TlsServer(SSLContext context) throws IOException {
            this(context, Duration.ZERO, false);
        }

        TlsServer(SSLContext context, Duration delayedBody, boolean splitBody) throws IOException {
            socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1,
                    InetAddress.getByName("127.0.0.1"));
            this.delayedBody = delayedBody;
            this.splitBody = splitBody;
            worker = Thread.startVirtualThread(this::serve);
        }

        int port() {
            return socket.getLocalPort();
        }

        String requestedSni() {
            return requestedSni.get();
        }

        String hostHeader() {
            return hostHeader.get();
        }

        Throwable failure() {
            return failure.get();
        }

        void await() throws InterruptedException {
            worker.join(Duration.ofSeconds(2));
            assertThat(worker.isAlive()).isFalse();
        }

        private void serve() {
            try (SSLSocket connection = (SSLSocket) socket.accept()) {
                connection.setSoTimeout((int) Duration.ofSeconds(2).toMillis());
                connection.startHandshake();
                if (connection.getSession() instanceof ExtendedSSLSession session) {
                    requestedSni.set(session.getRequestedServerNames().stream()
                            .filter(SNIHostName.class::isInstance)
                            .map(SNIHostName.class::cast)
                            .map(SNIHostName::getAsciiName)
                            .findFirst()
                            .orElse(null));
                }
                hostHeader.set(readHostHeader(connection.getInputStream()));
                if (!splitBody && !delayedBody.isZero()) Thread.sleep(delayedBody);
                connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 2\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                if (splitBody) {
                    connection.getOutputStream().write('o');
                    connection.getOutputStream().flush();
                }
                if (splitBody && !delayedBody.isZero()) Thread.sleep(delayedBody);
                if (splitBody) {
                    connection.getOutputStream().write('k');
                } else {
                    connection.getOutputStream().write("ok".getBytes(StandardCharsets.US_ASCII));
                }
                connection.getOutputStream().flush();
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }

        private static String readHostHeader(InputStream input) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Host:", 0, "Host:".length())) {
                    return line.substring("Host:".length()).trim();
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
