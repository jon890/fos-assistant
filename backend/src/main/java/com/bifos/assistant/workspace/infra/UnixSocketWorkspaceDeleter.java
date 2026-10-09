package com.bifos.assistant.workspace.infra;

import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.WorkspaceDeleter;
import com.bifos.assistant.workspace.domain.WorkspaceDeletion;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영의 권한 도우미를 unix socket 으로 불러 지운다. 계약은 {@code docs/code-architecture.md} 의 「지우기」 가 갖는다.
 *
 * <p>요청 하나에 연결 하나다. 연결과 쓰기와 읽기를 합쳐 제한 시간 안에 끝내지 못하면 채널을 닫는다. 제한 시간은 non-blocking 채널과
 * {@link Selector} 로 건다.
 */
@Component
@Slf4j
public class UnixSocketWorkspaceDeleter implements WorkspaceDeleter {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ANSWER_BYTES = 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LiveProperties<WorkspaceProperties> properties;
    private final Duration timeout;

    @Autowired
    public UnixSocketWorkspaceDeleter(LiveProperties<WorkspaceProperties> properties) {
        this(properties, DEFAULT_TIMEOUT);
    }

    UnixSocketWorkspaceDeleter(LiveProperties<WorkspaceProperties> properties, Duration timeout) {
        this.properties = properties;
        this.timeout = timeout;
    }

    @Override
    public WorkspaceDeletion delete(String owner, WorkspacePath path, int maxEntries) {
        WorkspaceProperties current = properties.current();
        if (!current.deletable()) {
            throw new ApiException(ErrorCode.WORKSPACE_DELETE_UNAVAILABLE, "workspace delete is not configured");
        }
        String request = JSON.writeValueAsString(JSON.createObjectNode()
                        .put("version", 1)
                        .put("owner", owner)
                        .put("path", path.value())
                        .put("max_entries", maxEntries))
                + "\n";
        String answer;
        try {
            answer = exchange(Path.of(current.deleteSocket()), request.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn(
                    "workspace delete helper exchange failed error={}",
                    ex.getClass().getSimpleName());
            throw failed("workspace delete helper exchange failed");
        }
        return parse(answer);
    }

    private String exchange(Path socket, byte[] request) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            channel.connect(UnixDomainSocketAddress.of(socket));
            return new String(exchangeBytes(channel, selector, request, deadline), StandardCharsets.UTF_8);
        }
    }

    /** 요청 줄을 보내고 답 한 줄을 읽는다. 줄바꿈 없이 닫히면 그때까지 받은 것을 답으로 본다. */
    private byte[] exchangeBytes(SocketChannel channel, Selector selector, byte[] request, long deadline)
            throws IOException {
        SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
        while (!channel.finishConnect()) {
            await(selector, deadline);
        }
        key.interestOps(SelectionKey.OP_WRITE);
        ByteBuffer out = ByteBuffer.wrap(request);
        while (out.hasRemaining()) {
            if (channel.write(out) == 0) {
                await(selector, deadline);
            }
        }
        key.interestOps(SelectionKey.OP_READ);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer in = ByteBuffer.allocate(8 * 1024);
        while (true) {
            int read = channel.read(in);
            if (read < 0) {
                return line.toByteArray();
            }
            if (read == 0) {
                await(selector, deadline);
                continue;
            }
            int end = indexOfNewline(in.array(), in.position());
            line.write(in.array(), 0, end < 0 ? in.position() : end);
            if (line.size() > MAX_ANSWER_BYTES) {
                throw new IOException("answer line is too long");
            }
            if (end >= 0) {
                return line.toByteArray();
            }
            in.clear();
        }
    }

    /** 제한 시간까지 남은 만큼 기다린다. 깨어난 까닭은 부르는 쪽이 채널을 다시 보고 판정한다. */
    private static void await(Selector selector, long deadline) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("workspace delete helper timed out");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("workspace delete interrupted");
        }
        // 밀리초로 내리면 제한 시간보다 일찍 포기하므로 올린다.
        selector.select(Math.ceilDiv(remainingNanos, 1_000_000L));
        selector.selectedKeys().clear();
    }

    private static int indexOfNewline(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static WorkspaceDeletion parse(String answer) {
        JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (JacksonException ex) {
            throw failed("workspace delete helper answered malformed JSON");
        }
        JsonNode ok = root == null ? null : root.get("ok");
        if (ok == null || !ok.isBoolean()) {
            throw failed("workspace delete helper answered without ok");
        }
        if (!ok.booleanValue()) {
            JsonNode code = root.get("code");
            String value = code != null && code.isString() ? code.asString() : "";
            throw switch (value) {
                case "NOT_FOUND", "LINK_IN_PATH" ->
                    new ApiException(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND, "workspace entry not found");
                case "TOO_MANY_ENTRIES" ->
                    new ApiException(ErrorCode.WORKSPACE_DELETE_TOO_MANY, "workspace entry has too many entries");
                default -> failed("workspace delete helper failed");
            };
        }
        JsonNode kind = root.get("kind");
        JsonNode entries = root.get("entries");
        JsonNode bytes = root.get("bytes");
        if (kind == null
                || !kind.isString()
                || Arrays.stream(WorkspaceEntryKind.values())
                        .noneMatch(value -> value.name().equals(kind.asString()))
                || !nonNegativeLong(entries)
                || !nonNegativeLong(bytes)) {
            throw failed("workspace delete helper answered unknown result");
        }
        return new WorkspaceDeletion(
                WorkspaceEntryKind.valueOf(kind.asString()), entries.longValue(), bytes.longValue());
    }

    private static boolean nonNegativeLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0;
    }

    private static ApiException failed(String message) {
        return new ApiException(ErrorCode.WORKSPACE_DELETE_FAILED, message);
    }
}
