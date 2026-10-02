package com.bifos.assistant.chat.infra;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ArtifactSourceSocketTransport implements ArtifactSourceTransport {
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int HTTPS_PORT = 443;

    private final SSLSocketFactory sslSocketFactory;
    private final int port;

    public ArtifactSourceSocketTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault(), HTTPS_PORT);
    }

    /** TLS 검증에 쓸 factory와 포트를 바꿀 수 있는 좁은 생성자다. */
    ArtifactSourceSocketTransport(SSLSocketFactory sslSocketFactory, int port) {
        if (sslSocketFactory == null || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("TLS transport settings are invalid");
        }
        this.sslSocketFactory = sslSocketFactory;
        this.port = port;
    }

    @Override
    public ArtifactSourceResponse get(
            InetAddress address,
            String host,
            URI source,
            Duration connectTimeout,
            Duration readTimeout,
            ArtifactSourceCancellation cancellation)
            throws IOException {
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
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    public static ArtifactSourceResponse parse(Socket socket, InputStream input) throws IOException {
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
            return new ArtifactSourceResponse(0, Map.of(), closeOnClose(input, socket));
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
        return new ArtifactSourceResponse(200, Map.copyOf(headers), closeOnClose(input, socket));
    }

    public static byte[] requestBytes(String target, String host) {
        return ("GET " + target + " HTTP/1.1\r\nHost: " + host
                        + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isHeaderName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static InputStream closeOnClose(InputStream input, Socket socket) {
        return new FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    socket.close();
                }
            }
        };
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream input;
        private long remaining = -1;
        private boolean done;

        ChunkedInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
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
            try {
                remaining = Long.parseLong(line.split(";", 2)[0], 16);
            } catch (NumberFormatException ex) {
                throw new IOException("invalid chunk size", ex);
            }
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

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1, current;
            while ((current = input.read()) >= 0) {
                if (previous == '\r' && current == '\n') {
                    byte[] b = line.toByteArray();
                    return new String(b, 0, b.length - 1, StandardCharsets.US_ASCII);
                }
                if (line.size() >= 1024) {
                    throw new IOException("chunk line too long");
                }
                line.write(current);
                previous = current;
            }
            throw new IOException("truncated chunk");
        }
    }
}
