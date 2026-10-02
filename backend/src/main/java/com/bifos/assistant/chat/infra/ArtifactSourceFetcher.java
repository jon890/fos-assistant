package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.application.ArtifactSourceProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SNIHostName;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 허용한 호스트를 검사한 공개 IP로만 HTTPS 이미지 하나를 가져온다. */
@Component
public class ArtifactSourceFetcher {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_HEADERS = 16 * 1024;
    private final ArtifactSourceProperties properties;
    private final DnsResolver dnsResolver;
    private final Transport transport;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), daemonFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public ArtifactSourceFetcher(ArtifactSourceProperties properties) {
        this(properties, InetAddress::getAllByName, new SocketTransport());
    }

    public ArtifactSourceFetcher(ArtifactSourceProperties properties, DnsResolver dnsResolver, Transport transport) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.transport = transport;
    }

    public byte[] fetch(URI source, String expectedContentType) {
        ValidSource valid = validate(source);
        Cancellation cancellation = new Cancellation();
        Future<byte[]> future;
        try {
            future = executor.submit(() -> fetchResolved(valid, expectedContentType, cancellation));
        } catch (RuntimeException ex) {
            throw failed("artifact source download is busy", ex);
        }
        try {
            return future.get(properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            cancellation.close();
            throw failed("artifact source download interrupted", ex);
        } catch (TimeoutException ex) {
            future.cancel(true);
            cancellation.close();
            throw failed("artifact source download timed out", ex);
        } catch (ExecutionException ex) {
            throw failed("could not download artifact source", ex.getCause());
        }
    }

    private byte[] fetchResolved(ValidSource source, String expectedContentType, Cancellation cancellation) throws Exception {
        InetAddress[] addresses = dnsResolver.resolve(source.host());
        if (addresses == null || addresses.length == 0) {
            throw failed("artifact source host has no addresses", null);
        }
        List<InetAddress> publicAddresses = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw failed("artifact source host resolves to a non-public address", null);
            }
            publicAddresses.add(address);
        }
        Throwable last = null;
        for (InetAddress address : publicAddresses) {
            try (Response response = transport.get(address, source.host(), source.uri(), properties.connectTimeout(), properties.readTimeout(), cancellation)) {
                return validateResponse(response, expectedContentType);
            } catch (IOException ex) {
                last = ex;
            }
        }
        throw failed("could not connect to artifact source", last);
    }

    private static byte[] validateResponse(Response response, String expectedContentType) throws IOException {
        if (response.status() != 200) {
            throw failed("artifact source did not return success", null);
        }
        String contentType = mediaType(response.headers().get("content-type"));
        if (!expectedContentType.equals(contentType) || response.headers().containsKey("content-encoding")) {
            throw failed("artifact source content type is invalid", null);
        }
        Long expectedLength = null;
        String length = response.headers().get("content-length");
        if (length != null) {
            long value;
            try { value = Long.parseLong(length); } catch (NumberFormatException ex) { throw failed("artifact source length is invalid", ex); }
            if (value < 0 || value > MAX_BYTES) {
                throw failed("artifact source is too large", null);
            }
            expectedLength = value;
        }
        return readLimited(response.body(), expectedLength);
    }

    private static byte[] readLimited(InputStream body, Long expectedLength) throws IOException {
        try (InputStream input = body; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                if (expectedLength != null && total == expectedLength) {
                    return output.toByteArray();
                }
                int wanted = Math.min(buffer.length, MAX_BYTES + 1 - total);
                if (expectedLength != null) {
                    wanted = Math.min(wanted, expectedLength.intValue() - total);
                }
                int read = input.read(buffer, 0, wanted);
                if (read < 0) {
                    if (expectedLength != null && total != expectedLength) {
                        throw failed("artifact source length is truncated", null);
                    }
                    return output.toByteArray();
                }
                total += read;
                if (total > MAX_BYTES) {
                    throw failed("artifact source is too large", null);
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private ValidSource validate(URI source) {
        if (source == null || !"https".equals(source.getScheme()) || source.getRawUserInfo() != null || source.getRawFragment() != null
                || source.getHost() == null || source.getHost().endsWith(".") || source.getPort() != -1 && source.getPort() != 443
                || source.getHost().indexOf(':') >= 0 || source.getHost().matches("[0-9.]+") || !source.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")) {
            throw failed("artifact source URL is invalid", null);
        }
        String host = source.getHost();
        if (!properties.allowedHosts().contains(host)) {
            throw failed("artifact source host is not allowed", null);
        }
        String raw = source.getRawPath() + (source.getRawQuery() == null ? "" : "?" + source.getRawQuery());
        String decoded = source.getPath() + (source.getQuery() == null ? "" : "?" + source.getQuery());
        if (hasControlCharacter(raw) || hasControlCharacter(decoded)) {
            throw failed("artifact source URL is invalid", null);
        }
        return new ValidSource(source, host);
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && bytes.length == 16 && isV4Mapped(bytes)) {
            return isPublicV4(bytes[12] & 255, bytes[13] & 255, bytes[14] & 255, bytes[15] & 255);
        }
        if (address instanceof Inet4Address) {
            return isPublicV4(bytes[0] & 255, bytes[1] & 255, bytes[2] & 255, bytes[3] & 255);
        }
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        int first = bytes[0] & 255;
        if ((first & 0xe0) != 0x20) {
            return false;
        }
        if (is6to4(bytes) || isTeredo(bytes)) {
            return false;
        }
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isMulticastAddress() && !address.isSiteLocalAddress() && (first & 0xfe) != 0xfc && first != 0xff && !isReservedV6(bytes);
    }

    private static boolean isPublicV4(int a, int b, int c, int d) {
        if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 192 && b == 0 && c == 0 && d != 9 && d != 10)
                || (a == 192 && b == 88 && c == 99)
                || (a == 198 && (b == 18 || b == 19)) || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)) {
            return false;
        }
        return true;
    }

    private static boolean isV4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean hasControlCharacter(String value) { return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f); }

    private static boolean is6to4(byte[] bytes) { return bytes[0] == 0x20 && bytes[1] == 0x02; }

    private static boolean isTeredo(byte[] bytes) { return bytes[0] == 0x20 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0; }

    private static boolean isReservedV6(byte[] bytes) {
        return (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00
                && (bytes[3] == 0x02 || (bytes[3] & 0xf0) == 0x10 || (bytes[3] & 0xf0) == 0x20))
                || (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8);
    }

    private static String mediaType(String value) { return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }

    private static ApiException failed(String message, Throwable cause) { return cause == null ? new ApiException(ErrorCode.INTERNAL_ERROR, message) : new ApiException(ErrorCode.INTERNAL_ERROR, message, cause); }

    private static ThreadFactory daemonFactory() { return runnable -> { Thread thread = new Thread(runnable, "artifact-source-fetcher"); thread.setDaemon(true); return thread; }; }

    public interface DnsResolver { InetAddress[] resolve(String host) throws Exception; }

    public interface Transport { Response get(InetAddress address, String originalHost, URI source, Duration connectTimeout, Duration readTimeout, Cancellation cancellation) throws IOException; }

    public record Response(int status, Map<String, String> headers, InputStream body) implements AutoCloseable { @Override public void close() throws IOException { body.close(); } }

    public static final class Cancellation {
        private final AtomicReference<Closeable> active = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        public void register(Closeable closeable) {
            if (closed.get()) { close(closeable); return; }
            active.set(closeable);
            if (closed.get()) {
                close(active.getAndSet(null));
            }
        }

        void close() { closed.set(true); close(active.getAndSet(null)); }

        private static void close(Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private record ValidSource(URI uri, String host) { }

    public static final class SocketTransport implements Transport {
        private static final int HTTPS_PORT = 443;

        private final SSLSocketFactory sslSocketFactory;
        private final int port;

        public SocketTransport() {
            this((SSLSocketFactory) SSLSocketFactory.getDefault(), HTTPS_PORT);
        }

        /** TLS 검증에 쓸 factory와 포트를 바꿀 수 있는 좁은 생성자다. */
        SocketTransport(SSLSocketFactory sslSocketFactory, int port) {
            if (sslSocketFactory == null || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("TLS transport settings are invalid");
            }
            this.sslSocketFactory = sslSocketFactory;
            this.port = port;
        }

        @Override public Response get(InetAddress address, String host, URI source, Duration connectTimeout, Duration readTimeout, Cancellation cancellation) throws IOException {
            Socket socket = new Socket();
            try {
                cancellation.register(socket);
                socket.connect(new InetSocketAddress(address, port), (int) connectTimeout.toMillis());
                socket.setSoTimeout((int) readTimeout.toMillis());
                SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(socket, host, port, true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                String target = source.getRawPath() == null || source.getRawPath().isEmpty() ? "/" : source.getRawPath();
                if (source.getRawQuery() != null) {
                    target += "?" + source.getRawQuery();
                }
                ssl.getOutputStream().write(requestBytes(target, host));
                ssl.getOutputStream().flush();
                return parse(ssl, ssl.getInputStream());
            } catch (IOException | RuntimeException ex) { try { socket.close(); } catch (IOException ignored) { } throw ex; }
        }

        public static Response parse(Socket socket, InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int ending = 0;
            int current;
            boolean complete = false;
            while ((current = input.read()) >= 0) {
                header.write(current);
                if (header.size() > MAX_HEADERS) {
                    throw new IOException("response headers are too large");
                }
                ending = (ending << 8) | current;
                if (header.size() >= 4 && ending == 0x0d0a0d0a) {
                    complete = true;
                    break;
                }
            }
            if (!complete) {
                throw new IOException("truncated response headers");
            }
            String[] lines = header.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] 200 .*")) {
                return new Response(0, Map.of(), closeOnClose(input, socket));
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) {
                    throw new IOException("invalid response header");
                }
                String key = lines[i].substring(0, colon);
                if (!isHeaderName(key)) {
                    throw new IOException("invalid response header");
                }
                key = key.toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (headers.putIfAbsent(key, value) != null) {
                    throw new IOException("duplicate response header");
                }
            }
            if (headers.containsKey("transfer-encoding") && headers.containsKey("content-length")) {
                throw new IOException("ambiguous response framing");
            }
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                input = new ChunkedInputStream(input);
            } else if (headers.containsKey("transfer-encoding")) {
                throw new IOException("unsupported response framing");
            }
            return new Response(200, Map.copyOf(headers), closeOnClose(input, socket));
        }

        public static byte[] requestBytes(String target, String host) {
            return ("GET " + target + " HTTP/1.1\r\nHost: " + host
                    + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        }

        private static boolean isHeaderName(String value) {
            if (value.isEmpty()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!(character >= '0' && character <= '9') && !(character >= 'A' && character <= 'Z')
                        && !(character >= 'a' && character <= 'z') && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
                    return false;
                }
            }
            return true;
        }

        private static InputStream closeOnClose(InputStream input, Socket socket) { return new FilterInputStream(input) { @Override public void close() throws IOException { try { super.close(); } finally { socket.close(); } } }; }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream input; private long remaining = -1; private boolean done;

        ChunkedInputStream(InputStream input) { this.input = input; }

        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one) < 0 ? -1 : one[0] & 255; }

        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            if (done) {
                return -1;
            }
            if (remaining == 0 || remaining < 0) {
                next();
            }
            if (done) {
                return -1;
            }
            int count = input.read(bytes, off, (int) Math.min(len, remaining));
            if (count < 0) {
                throw new IOException("truncated chunk");
            }
            remaining -= count;
            if (remaining == 0) {
                if (input.read() != '\r' || input.read() != '\n') {
                    throw new IOException("invalid chunk ending");
                }
            }
            return count;
        }

        private void next() throws IOException {
            String line = readLine(input);
            try { remaining = Long.parseLong(line.split(";", 2)[0], 16); }
            catch (NumberFormatException ex) { throw new IOException("invalid chunk size", ex); }
            if (remaining < 0) {
                throw new IOException("invalid chunk size");
            }
            if (remaining == 0) {
                int trailerBytes = 0;
                String trailer;
                do {
                    trailer = readLine(input);
                    trailerBytes += trailer.length() + 2;
                    if (trailerBytes > MAX_HEADERS) {
                        throw new IOException("chunk trailer is too large");
                    }
                } while (!trailer.isEmpty());
                done = true;
            }
        }

        private static String readLine(InputStream input) throws IOException { ByteArrayOutputStream line = new ByteArrayOutputStream(); int previous = -1, current; while ((current = input.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                byte[] b = line.toByteArray();
                return new String(b, 0, b.length - 1, StandardCharsets.US_ASCII);
            }
            if (line.size() >= 1024) {
                throw new IOException("chunk line too long");
            }
            line.write(current);
            previous = current;
        } throw new IOException("truncated chunk"); }
    }
}
