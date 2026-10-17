package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 금융 줄의 DB 해시 칸을 서버 비밀로 keyed hash 한다(#507 후속).
 *
 * <p>key 없는 SHA 는 DB 만 읽은 사람이 인자 후보를 대입해 맞혀 볼 수 있다. 계좌 순번 하나뿐인 scope 는 후보가 특히 적다.
 * 그래서 저장하는 칸은 이 값으로만 적는다. Hermes 와 SDK 가 다시 계산해 견주는 전송 해시는 평문 SHA-256 그대로 두고 저장하지 않는다.
 *
 * <p>값은 {@value #PREFIX}, key 식별자 8글자, HMAC-SHA256 앞 27바이트의 16진수로 64글자다. 평문 16진수 해시에는 {@code k} 가 없어 옛 줄을 값으로 가린다.
 * key 식별자는 key 로 고정 문자열을 HMAC 한 앞 4바이트다. key 를 드러내지 않고, 저장 줄이 지금 key 로 만든 것인지 DB 조회로 가르게 한다.
 * key 를 바꾸면 같은 의도도 다른 request_key 가 되므로, 금융 관문은 다른 key 로 만든 열린 줄이 남으면 닫는다.
 * key 는 운영 비밀 파일 하나에서 읽는다. 파일이 없거나 짧으면 쓸 수 없고, 금융 관문이 닫힌다.
 */
@Component
@RequiredArgsConstructor
public class FinancialDigest {
    public static final String PREFIX = "k1";
    private static final int MIN_KEY_BYTES = 32;
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private volatile Loaded loaded;


    /** key 를 읽을 수 있는지다. 금융 관문이 열기 전에 본다. */
    public boolean available() {
        return key() != null;
    }


    /** 칸 이름을 목적으로 넣어 같은 원문도 칸마다 다른 값이 되게 한다. */
    public String of(String purpose, String value) {
        Loaded current = load();
        if (current.key() == null || purpose == null || value == null) {
            throw new IllegalStateException("financial digest unavailable");
        }
        byte[] digest = hmac(current.key(), "fos-financial-digest-v1\0" + purpose + "\0" + value);
        return current.prefix() + HexFormat.of().formatHex(digest, 0, 27);
    }


    /** 지금 key 로 만든 값이 공통으로 갖는 앞 10글자다. key 가 없으면 쓸 수 없다. */
    public String currentPrefix() {
        Loaded current = load();
        if (current.key() == null) {
            throw new IllegalStateException("financial digest unavailable");
        }
        return current.prefix();
    }


    /** 이 key 로 만든 값인지가 아니라, keyed hash 모양인지만 본다. 옛 평문 해시를 가르는 데 쓴다. */
    public static boolean keyed(String stored) {
        return stored != null && stored.length() == 64 && stored.startsWith(PREFIX);
    }


    private byte[] key() {
        return load().key();
    }


    private Loaded load() {
        String file = properties.current().digestKeyFile();
        Loaded current = loaded;
        if (current != null && current.file().equals(file)) {
            return current;
        }
        byte[] key = read(file);
        current = new Loaded(file, key,
            key == null ? null : PREFIX + HexFormat.of().formatHex(hmac(key, "fos-financial-digest-key-id-v1"), 0, 4));
        loaded = current;
        return current;
    }


    private static byte[] hmac(byte[] key, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("financial digest unavailable");
        }
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


    private record Loaded(String file, byte[] key, String prefix) {
    }
}
