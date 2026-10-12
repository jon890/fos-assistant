package com.bifos.assistant.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Python 계약 원본의 버전로 공유 wire를 고른다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HermesWire {
    private static final Pattern VERSION = Pattern.compile("(?m)^HERMES_VERSION = \"([^\"]+)\"$");


    public static String version() throws IOException {
        var match = VERSION.matcher(resource("/hermes/hermes_contract.py"));
        if (!match.find()) {
            throw new IOException("Hermes 계약의 버전을 읽을 수 없다");
        }
        return match.group(1);
    }


    public static String fixture(String name) throws IOException {
        return resource("/hermes/" + version() + "/" + name + ".sse");
    }


    private static String resource(String name) throws IOException {
        try (var input = HermesWire.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Hermes 테스트 리소스가 없다: " + name
                    + ". ./gradlew processTestResources를 먼저 실행한다.");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
