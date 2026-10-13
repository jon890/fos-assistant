package com.bifos.assistant.connector.application;

import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 금융 줄의 DB 해시 칸을 keyed hash 할 key 를 읽는다(#507 후속).
 *
 * <p>key 를 쓰는 계산은 다음 단계가 더한다. 이 단계는 key 를 읽을 수 있을 때만 금융 관문이 열리게 해,
 * keyed hash 없이 금융이 열리는 일을 코드로 막는다. key 는 운영 비밀 파일 하나에서 읽는다. 파일이 없거나 짧으면 쓸 수 없다.
 */
@Component
@RequiredArgsConstructor
public class FinancialDigest {
    private static final int MIN_KEY_BYTES = 32;
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private volatile Loaded loaded;


    /** key 를 읽을 수 있는지다. 금융 관문이 열기 전에 본다. */
    public boolean available() {
        return key() != null;
    }


    private byte[] key() {
        String file = properties.current().digestKeyFile();
        Loaded current = loaded;
        if (current != null && current.file().equals(file)) {
            return current.key();
        }
        byte[] key = read(file);
        loaded = new Loaded(file, key);
        return key;
    }


    /** {@code #} 로 시작하는 줄과 빈 줄을 빼고 남은 한 줄을 base64 로 읽는다. 읽지 못하면 null 이다. */
    private static byte[] read(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
            if (lines.size() != 1) {
                return null;
            }
            byte[] key = Base64.getDecoder().decode(lines.getFirst());
            return key.length >= MIN_KEY_BYTES ? key : null;
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }


    private record Loaded(String file, byte[] key) {
    }
}
