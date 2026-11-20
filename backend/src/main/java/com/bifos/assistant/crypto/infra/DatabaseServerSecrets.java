package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.SecretPrefix;
import com.bifos.assistant.crypto.domain.ServerSecretRecord;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.crypto.domain.entity.ServerSecret;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버 비밀값을 KEK 로 바로 감싸 {@code server_secret} 에 둔다(ADR-20261120 / server-secret).
 *
 * <p>AAD 는 {@code server_secret:<이름>} 이다. 암호문을 다른 이름의 줄로 옮기면 풀리지 않는다.
 *
 * <p>푼 값은 메모리에 둔다. 쓰기는 부른 쪽 트랜잭션이 커밋된 뒤(트랜잭션이 없으면 저장 직후) 새 값을 캐시에 넣고 그 이름의 세대를
 * 올린다. 읽기는 줄을 읽기 전에 세대를 적어 두고, 푼 뒤에도 세대가 같을 때만 캐시에 넣는다. 그래서 쓰기 커밋 전에 옛 줄을 읽은 스레드가
 * 커밋 뒤에 옛 값을 캐시에 남기지 못한다. 서버 한 대 전제라 다른 프로세스의 쓰기는 보지 않는다.
 *
 * <p>로그에는 이름과 사용자 번호, 예외 클래스 이름만 남긴다. 값과 앞부분은 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseServerSecrets implements ServerSecrets {

    private static final String AAD_PREFIX = "server_secret:";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> META = new TypeReference<>() {
    };

    private final ServerSecretRepository rows;
    private final KeyEncryptionKeys keks;

    /** 이름마다 푼 값. 비어 있음도 담는다 */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();


    @Override
    public boolean available() {
        return keks.available();
    }


    @Override
    public Optional<String> read(String name) {
        Optional<String> cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        long generation = generation(name).get();
        Optional<String> value = rows.findById(name).flatMap(row -> open(row));
        if (generation(name).get() == generation) {
            cache.putIfAbsent(name, value);
        }
        return value;
    }


    @Override
    public Optional<ServerSecretRecord> find(String name) {
        return rows.findById(name).map(DatabaseServerSecrets::record);
    }


    @Override
    public List<ServerSecretRecord> findByPrefix(String namePrefix) {
        return rows.findByNameStartingWithOrderByNameAsc(namePrefix).stream().map(DatabaseServerSecrets::record).toList();
    }


    @Override
    public boolean exists(String name) {
        return rows.existsById(name);
    }


    @Override
    public void write(String name, String value, Map<String, Object> meta, Long updatedBy) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("server secret value is empty");
        }
        if (!keks.available()) {
            throw new ApiException(ErrorCode.SECRET_STORE_UNAVAILABLE, "KEK is not configured");
        }
        String sealed = keks.wrap(value.getBytes(StandardCharsets.UTF_8), aad(name));
        ServerSecret row = rows.findById(name).orElseGet(() -> ServerSecret.named(name));
        row.seal(keks.activeKeyId(), sealed, SecretPrefix.of(value), metaJson(meta), updatedBy);
        rows.save(row);
        afterCommit(name, Optional.of(value));
        log.info("server secret written name={} by={}", name, updatedBy);
    }


    @Override
    public void clear(String name, Long updatedBy) {
        ServerSecret row = rows.findById(name).orElseGet(() -> ServerSecret.named(name));
        row.clear(updatedBy);
        rows.save(row);
        afterCommit(name, Optional.empty());
        log.info("server secret cleared name={} by={}", name, updatedBy);
    }


    /**
     * 활성 KEK 가 아닌 KEK 로 감싼 줄을 다시 감싼다. 기동 때 {@code DataKeyRewrapper} 가 부른다. 바꾼 시각과 사람은 그대로 둔다.
     *
     * @param eachInTransaction 줄 하나의 갱신을 자기 트랜잭션에서 돌리는 함수. 풀지 못한 줄과 갱신이 실패한 줄은 건너뛰고 수만 센다
     *
     * @return 다시 감싼 줄 수
     */
    public int rewrapStale(Consumer<Runnable> eachInTransaction) {
        if (!keks.available()) {
            return 0;
        }
        int rewrapped = 0;
        int missing = 0;
        int failed = 0;
        for (ServerSecret row : rows.findByKekIdNotAndKekIdIsNotNull(keks.activeKeyId())) {
            if (!keks.has(row.kekId())) {
                missing++;
                continue;
            }
            try {
                byte[] value = keks.unwrap(row.kekId(), row.sealedValue(), aad(row.name()));
                String sealed = keks.wrap(value, aad(row.name()));
                String kekId = keks.activeKeyId();
                // 줄마다 따로 커밋한다. 한 줄의 DB 오류가 앞서 다시 감싼 줄까지 되돌리지 않게 한다.
                eachInTransaction.accept(() -> rows.rewrap(row.name(), kekId, sealed));
                rewrapped++;
            } catch (RuntimeException ex) {
                // 한 줄이 풀리지 않아도 나머지 줄은 다시 감싼다. 이름과 예외 종류만 남긴다.
                log.warn("server secret cannot be rewrapped name={} exception={}", row.name(), ex.getClass().getName());
                failed++;
            }
        }
        if (rewrapped > 0) {
            log.info("서버 비밀값을 활성 KEK 로 다시 감쌌다 rewrapped={}", rewrapped);
        }
        if (missing > 0) {
            log.warn("KEK 파일에 없는 옛 KEK 로 감싼 서버 비밀값이 있다. 그 값은 읽지 못한다 count={}", missing);
        }
        if (failed > 0) {
            log.warn("다시 감싸지 못한 서버 비밀값이 있다 count={}", failed);
        }
        invalidateAll();
        return rewrapped;
    }


    /** 캐시를 모두 비우고 세대를 올린다. KEK 를 다시 감싼 뒤와 테스트 정리에 쓴다 */
    public void invalidateAll() {
        generations.values().forEach(AtomicLong::incrementAndGet);
        cache.clear();
    }


    private void afterCommit(String name, Optional<String> value) {
        Runnable apply = () -> {
            generation(name).incrementAndGet();
            cache.put(name, value);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apply.run();
                }
            });
        } else {
            apply.run();
        }
    }


    private Optional<String> open(ServerSecret row) {
        if (!row.set()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(keks.unwrap(row.kekId(), row.sealedValue(), aad(row.name())), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            log.warn("server secret cannot be opened name={} exception={}", row.name(), ex.getClass().getName());
            return Optional.empty();
        }
    }


    private AtomicLong generation(String name) {
        return generations.computeIfAbsent(name, key -> new AtomicLong());
    }


    private static byte[] aad(String name) {
        return (AAD_PREFIX + name).getBytes(StandardCharsets.UTF_8);
    }


    private static String metaJson(Map<String, Object> meta) {
        return meta == null || meta.isEmpty() ? null : JSON.writeValueAsString(meta);
    }


    private static ServerSecretRecord record(ServerSecret row) {
        Map<String, Object> meta;
        try {
            meta = row.metaJson() == null ? Map.of() : JSON.readValue(row.metaJson(), META);
        } catch (JacksonException ex) {
            log.warn("server secret meta is not JSON name={}", row.name());
            meta = Map.of();
        }
        return new ServerSecretRecord(row.name(), row.set(), row.prefix(), meta, row.updatedBy(), row.updatedAt());
    }
}
