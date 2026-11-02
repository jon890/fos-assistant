package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그룹이 쓰는 collection 의 목록을 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄에서 읽어 낸다(ADR-052,
 * ADR-20261102 / table-reduce-r2).
 *
 * <p>JSON 은 {@code {"version":1,"collections":[{"key":"core","name":"기본"}, ...]}} 이고 목록 순서가 화면 순서다.
 * 줄이 없는 그룹은 {@link MemoryCollection#DEFAULTS} 를 내고 저장하지 않는다. 읽을 때 모르는 버전이거나 형식이 깨졌으면
 * 기본 목록으로 대신하지 않고 오류로 올린다. 대신하면 그룹이 정한 목록이 화면과 에이전트 접근 설정에서 조용히 바뀐다.
 */
@Service
@RequiredArgsConstructor
public class MemoryCollectionService {

    /** 지금 쓰는 JSON 의 버전이다. 모양을 바꾸면 올리고, 옛 버전을 읽는 길을 함께 둔다. */
    static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GroupSettingStore settings;


    /** 그룹의 collection 을 화면 순서로 낸다. 줄이 없는 그룹은 기본 목록이다. */
    @Transactional(readOnly = true)
    public List<MemoryCollection> collectionsOf(Long groupId) {
        return settings.read(groupId, GroupSetting.MEMORY_COLLECTIONS)
            .map(MemoryCollectionService::decode)
            .orElse(MemoryCollection.DEFAULTS);
    }


    /**
     * 저장된 JSON 을 collection 목록으로 읽는다. 마이그레이션 검사가 옮긴 JSON 을 같은 해석으로 읽으려고 연다.
     *
     * @throws IllegalStateException 모르는 버전이거나 형식이 깨졌거나 목록이 비었거나 key 가 겹칠 때
     */
    public static List<MemoryCollection> decode(String value) {
        Stored stored;
        try {
            stored = JSON.readValue(value, Stored.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("stored memory collections setting is not readable", ex);
        }
        if (stored == null || stored.version() == null || stored.version() != VERSION) {
            throw new IllegalStateException("stored memory collections setting has an unknown version");
        }
        if (stored.collections() == null || stored.collections().isEmpty()) {
            throw new IllegalStateException("stored memory collections setting is incomplete");
        }
        Set<String> keys = new HashSet<>();
        for (StoredCollection collection : stored.collections()) {
            if (collection == null || isBlank(collection.key()) || isBlank(collection.name())
                || !keys.add(collection.key())) {
                throw new IllegalStateException("stored memory collections setting is incomplete");
            }
        }
        return stored.collections().stream()
            .map(collection -> new MemoryCollection(collection.key(), collection.name()))
            .toList();
    }


    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }


    /** 저장하는 JSON 의 모양이다. 칸 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(Integer version, List<StoredCollection> collections) {
    }

    private record StoredCollection(String key, String name) {
    }
}
