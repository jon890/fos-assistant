package com.bifos.assistant.chat.artifact.infra;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ArtifactSourceSocketTransport implements ArtifactSourceTransport, LinkImageTransport {
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int HTTPS_PORT = 443;
    /** 링크 받기에서 두 번 오면 안 되는 응답 머리다. 나머지(`set-cookie` 등)는 처음 값만 남긴다. */
    private static final Set<String> SINGLE_HEADERS = Set.of(
        "content-type", "content-length", "transfer-encoding", "content-encoding", "location");

    private final SSLSocketFactory sslSocketFactory;
    private final int port;


    public ArtifactSourceSocketTransport() {
        this((SSLSocketFactory)SSLSocketFactory.getDefault(), HTTPS_PORT);
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
        SSLSocket ssl = open(address, host, connectTimeout, readTimeout, cancellation);
        try {
            ssl.getOutputStream().write(requestBytes(target(source), host));
            ssl.getOutputStream().flush();
            return parse(ssl, ssl.getInputStream());
        } catch (IOException | RuntimeException ex) {
            closeQuietly(ssl);
            throw ex;
        }
    }


    @Override
    public ArtifactSourceResponse get(
        InetAddress address, String host, LinkImageRequest request, ArtifactSourceCancellation cancellation)
        throws IOException {
        SSLSocket ssl = open(address, host, request.connectTimeout(), request.readTimeout(), cancellation);
        try {
            ssl.getOutputStream().write(requestBytes(target(request.source()), host, request.headers()));
            ssl.getOutputStream().flush();
            return parseAnyStatus(ssl, ssl.getInputStream(), request.maxHeaderBytes());
        } catch (IOException | RuntimeException ex) {
            closeQuietly(ssl);
            throw ex;
        }
    }


    private SSLSocket open(
        InetAddress address, String host, Duration connectTimeout, Duration readTimeout, ArtifactSourceCancellation cancellation)
        throws IOException {
        Socket socket = new Socket();
        try {
            cancellation.register(socket);
            socket.connect(new InetSocketAddress(address, port), (int)connectTimeout.toMillis());
            socket.setSoTimeout((int)readTimeout.toMillis());
            SSLSocket ssl = (SSLSocket)sslSocketFactory.createSocket(socket, host, port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException ex) {
            closeQuietly(socket);
            throw ex;
        }
    }


    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }


    private static String target(URI source) {
        String target = source.getRawPath() == null || source.getRawPath().isEmpty() ? "/" : source.getRawPath();
        if (source.getRawQuery() != null) {
            target += "?" + source.getRawQuery();
        }
        return target;
    }


    /** 스티커 받기의 해석이다. 200 이 아니면 상태 0 과 빈 머리를 돌려주고, 같은 머리가 두 번 오면 거절한다. */
    public static ArtifactSourceResponse parse(Socket socket, InputStream input) throws IOException {
        String[] lines = readHeaderLines(input, MAX_HEADERS);
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] 200 .*")) {
            return new ArtifactSourceResponse(0, Map.of(), closeOnClose(input, socket));
        }
        return framed(socket, input, 200, headers(lines, null));
    }


    /**
     * 링크 받기의 해석이다. 상태 코드와 머리를 늘 읽는다. {@link #SINGLE_HEADERS} 만 두 번 올 때 거절하고 나머지는 처음 값만 남긴다.
     *
     * @param maxHeaderBytes 응답 머리의 상한
     */
    public static ArtifactSourceResponse parseAnyStatus(Socket socket, InputStream input, int maxHeaderBytes)
        throws IOException {
        String[] lines = readHeaderLines(input, maxHeaderBytes);
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [1-5][0-9][0-9]( .*)?")) {
            throw new IOException("invalid status line");
        }
        int status = Integer.parseInt(lines[0].substring(9, 12));
        return framed(socket, input, status, headers(lines, SINGLE_HEADERS));
    }


    private static String[] readHeaderLines(InputStream input, int maxHeaderBytes) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int ending = 0;
        int current;
        while ((current = input.read()) >= 0) {
            header.write(current);
            if (header.size() > maxHeaderBytes) {
                throw new IOException("response headers are too large");
            }
            ending = (ending << 8) | current;
            if (header.size() >= 4 && ending == 0x0d0a0d0a) {
                return header.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            }
        }
        throw new IOException("truncated response headers");
    }


    /**
     * @param single 두 번 오면 거절할 머리. null 이면 모든 머리다
     */
    private static Map<String, String> headers(String[] lines, Set<String> single) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
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
            if (headers.putIfAbsent(key, value) != null && (single == null || single.contains(key))) {
                throw new IOException("duplicate response header");
            }
        }
        return headers;
    }


    private static ArtifactSourceResponse framed(Socket socket, InputStream input, int status, Map<String, String> headers)
        throws IOException {
        if (headers.containsKey("transfer-encoding") && headers.containsKey("content-length")) {
            throw new IOException("ambiguous response framing");
        }
        InputStream body = input;
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = new ChunkedInputStream(input);
        } else if (headers.containsKey("transfer-encoding")) {
            throw new IOException("unsupported response framing");
        }
        return new ArtifactSourceResponse(status, Map.copyOf(headers), closeOnClose(body, socket));
    }


    public static byte[] requestBytes(String target, String host) {
        return requestBytes(target, host, Map.of("Accept-Encoding", "identity"));
    }


    /**
     * @param headers 더할 요청 머리. 이름과 값에 CR, LF 가 있으면 거절한다
     */
    static byte[] requestBytes(String target, String host, Map<String, String> headers) {
        StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: ").append(host);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!isHeaderName(header.getKey()) || header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0) {
                throw new IllegalArgumentException("request header is invalid");
            }
            request.append("\r\n").append(header.getKey()).append(": ").append(header.getValue());
        }
        return request.append("\r\nConnection: close\r\n\r\n").toString().getBytes(StandardCharsets.US_ASCII);
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
            int count = input.read(bytes, off, (int)Math.min(len, remaining));
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
