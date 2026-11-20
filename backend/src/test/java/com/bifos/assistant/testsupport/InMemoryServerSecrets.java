package com.bifos.assistant.testsupport;

import com.bifos.assistant.crypto.domain.SecretPrefix;
import com.bifos.assistant.crypto.domain.ServerSecretRecord;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** 스프링 컨텍스트 없이 도는 단위 검사가 쓰는 메모리 서버 비밀값이다. 암호화하지 않는다. */
public final class InMemoryServerSecrets implements ServerSecrets {

    private final Map<String, String> values = new ConcurrentSkipListMap<>();
    private final Map<String, Long> updatedBy = new ConcurrentSkipListMap<>();


    /** 값 하나를 넣은 채로 만든다. 값이 null 이면 비어 있다 */
    public static InMemoryServerSecrets with(String name, String value) {
        InMemoryServerSecrets secrets = new InMemoryServerSecrets();
        if (value != null) {
            secrets.values.put(name, value);
        }
        return secrets;
    }


    @Override
    public boolean available() {
        return true;
    }


    @Override
    public Optional<String> read(String name) {
        return Optional.ofNullable(values.get(name)).filter(value -> !value.isEmpty());
    }


    @Override
    public Optional<ServerSecretRecord> find(String name) {
        if (!values.containsKey(name)) {
            return Optional.empty();
        }
        String value = values.get(name);
        boolean set = !value.isEmpty();
        return Optional.of(
            new ServerSecretRecord(name, set, set ? SecretPrefix.of(value) : null, Map.of(), updatedBy.get(name), Instant.EPOCH));
    }


    @Override
    public List<ServerSecretRecord> findByPrefix(String namePrefix) {
        return values.keySet().stream().filter(name -> name.startsWith(namePrefix)).map(name -> find(name).orElseThrow()).toList();
    }


    @Override
    public boolean exists(String name) {
        return values.containsKey(name);
    }


    @Override
    public void write(String name, String value, Map<String, Object> meta, Long by) {
        values.put(name, value);
        if (by != null) {
            updatedBy.put(name, by);
        }
    }


    @Override
    public void clear(String name, Long by) {
        values.put(name, "");
        if (by != null) {
            updatedBy.put(name, by);
        }
    }
}
