package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 실행 주인의 첨부 디렉터리 {@code {root}/users/{sha256(주인)}} 를 Hermes 를 부르기 전에 만든다(ADR-090).
 *
 * <p>Hermes 는 첨부 루트를 읽기 전용으로 보므로 이 디렉터리를 만들지 못한다. 사진을 한 번도 올리지 않은 주인도 실행 공간을
 * 쓰므로, 실행 공간 설정을 쓰는 세 호출(도구 저장, 옛 커넥터 설치, 스킬 게시)이 보내기 전에 이것을 부른다. plugin 은 존재와
 * 링크 없음만 확인한다.
 */
@Component
public class SandboxAttachmentDirectory {

    /** plugin 의 {@code SANDBOX_OWNER_RE} 와 같다. */
    private static final Pattern OWNER = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    private static final String REJECTED_MESSAGE = "the attachment directory of the execution owner is not available";

    private final Path root;

    public SandboxAttachmentDirectory(@Value("${assistant.attachment.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** 주인 문자열의 UTF-8 SHA-256 소문자 64자리다. 비밀값이나 권한이 아니라 경로에 주인을 드러내지 않는 이름이다. */
    public static String key(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            throw new IllegalArgumentException("sandbox owner is not valid");
        }
        return Sha256.hex(owner);
    }

    /**
     * 주인의 디렉터리를 만들거나 이미 있는 것을 확인한다.
     *
     * <p>루트와 그 아래 경로에 심볼릭 링크가 없어야 하고, 실제 경로가 루트의 실제 경로 아래 그 디렉터리와 같아야 한다.
     * 아니면 Hermes 가 거절한 것과 같은 409 로 멈춘다. Hermes 에는 아무것도 보내지 않았다.
     *
     * @throws HermesRequestRejected 디렉터리를 만들 수 없거나 링크를 거쳐 다른 곳을 가리킨다
     */
    public synchronized void ensure(String owner) {
        String key = key(owner);
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("attachment root is not a plain directory");
            }
            Path directory =
                    plainDirectory(plainDirectory(root.resolve("users")).resolve(key));
            if (!directory
                    .toRealPath()
                    .equals(root.toRealPath().resolve("users").resolve(key))) {
                throw new IOException("attachment directory resolves outside the execution owner");
            }
        } catch (IOException ex) {
            throw new HermesRequestRejected(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, REJECTED_MESSAGE, 409, ex);
        }
    }

    private static Path plainDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException ignored) {
            // 이미 있으면 아래에서 링크가 아닌 디렉터리인지 본다. 깨진 링크도 여기로 온다.
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("attachment path is not a plain directory");
        }
        return path;
    }
}
