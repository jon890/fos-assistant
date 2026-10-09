package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.AesGcm;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 서버 파일에서 KEK 목록을 읽는 구현이다(ADR-20261008 / data-encryption).
 *
 * <p>파일은 기동할 때 한 번 읽는다. 한 줄에 {@code <id>:<base64 32바이트>} 하나이고, 빈 줄과 {@code #} 로 시작하는 줄은
 * 건너뛴다. 모양이 틀리면 기동하지 않는다. 예외 메시지와 로그에 key 값과 파일 경로를 넣지 않는다.
 *
 * <p>파일을 그룹이나 다른 사용자가 읽을 수 있으면 경고만 남긴다. 컨테이너 secret 은 권한을 고칠 수 없는 경우가 있다.
 */
@Component
@Slf4j
public class FileKeyEncryptionKeys implements KeyEncryptionKeys {

    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final int KEY_BYTES = 32;
    private static final Set<PosixFilePermission> OTHERS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE);

    private final String activeKeyId;
    private final Map<String, SecretKey> keys;

    public FileKeyEncryptionKeys(DataEncryptionProperties properties) {
        if (!properties.configured()) {
            this.activeKeyId = "";
            this.keys = Map.of();
            log.warn("KEK 가 설정되지 않아 새 대화 본문을 평문으로 저장한다");
            return;
        }
        Path file = Path.of(properties.kekFile());
        this.keys = Map.copyOf(read(file));
        if (!keys.containsKey(properties.activeKekId())) {
            throw new IllegalStateException("data encryption: active-kek-id 가 KEK 파일에 없다");
        }
        this.activeKeyId = properties.activeKekId();
        warnIfShared(file);
    }

    @Override
    public boolean available() {
        return !activeKeyId.isEmpty();
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public boolean has(String keyId) {
        return keys.containsKey(keyId);
    }

    @Override
    public String wrap(byte[] dataKey, byte[] aad) {
        if (!available()) {
            throw new IllegalStateException("KEK is not configured");
        }
        return AesGcm.seal(keys.get(activeKeyId), dataKey, aad);
    }

    @Override
    public byte[] unwrap(String keyId, String wrapped, byte[] aad) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("KEK is not available");
        }
        return AesGcm.open(key, wrapped, aad);
    }

    private static Map<String, SecretKey> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("data encryption: KEK 파일을 읽지 못했다");
        }
        Map<String, SecretKey> parsed = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            String id = colon < 0 ? "" : line.substring(0, colon).strip();
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalStateException("data encryption: KEK 파일 " + (index + 1) + "번째 줄의 모양이 틀렸다");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(line.substring(colon + 1).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("data encryption: KEK 값이 base64 가 아니다: " + id);
            }
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException("data encryption: KEK 값이 32바이트가 아니다: " + id);
            }
            if (parsed.put(id, new SecretKeySpec(bytes, "AES")) != null) {
                throw new IllegalStateException("data encryption: KEK id 가 겹친다: " + id);
            }
        }
        return parsed;
    }

    private static void warnIfShared(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.stream().anyMatch(OTHERS::contains)) {
                log.warn("KEK 파일을 소유자가 아닌 사용자도 읽을 수 있다. 소유자만 읽게 권한을 줄인다");
            }
        } catch (UnsupportedOperationException | IOException e) {
            // POSIX 권한이 없는 파일 시스템이다. 권한 검사는 운영 설정이 맡는다.
        }
    }
}
