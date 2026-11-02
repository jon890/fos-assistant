package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그룹이 숨긴 toolset 목록을 {@code group_setting} 의 {@code TOOLSET} 줄 하나로 읽고 쓴다(ADR-20261102 /
 * table-reduce-r2). 숨기거나 다시 보여도 Hermes의 활성 도구는 바꾸지 않는다.
 *
 * <p>JSON 은 {@code {"version":1,"hidden":["이름", ...]}} 이고 이름은 이름 순에 중복이 없다. 줄이 없으면 숨김이
 * 없다. 읽을 때 모르는 버전이거나 형식이 깨졌으면 숨김이 없는 것으로 보지 않고 오류로 올린다. 없는 것으로 보면 관리자가
 * 숨긴 toolset 이 화면과 요청 검증에서 풀린다.
 */
@Service
@RequiredArgsConstructor
public class ToolsetVisibilityService {

    /** 지금 쓰는 JSON 의 버전이다. 모양을 바꾸면 올리고, 옛 버전을 읽는 길을 함께 둔다. */
    static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GroupSettingStore settings;


    @Transactional(readOnly = true)
    public Set<String> hiddenFor(Long groupId) {
        return settings.read(groupId, GroupSetting.TOOLSET)
            .<Set<String>>map(value -> Set.copyOf(decode(value)))
            .orElseGet(Set::of);
    }


    /** 그룹의 숨김 목록 전체를 바꾼다. 잠그고 읽은 뒤 쓰므로 동시에 온 다른 저장 뒤에 차례로 반영된다. */
    @Transactional
    public void save(CurrentUser user, List<String> names) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only admins can hide toolsets");
        }
        if (names == null || names.stream().anyMatch(name -> name == null || !AgentToolPolicy.isKnown(name))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown hidden toolset");
        }
        settings.readForUpdate(user.groupId(), GroupSetting.TOOLSET);
        settings.write(user.groupId(), GroupSetting.TOOLSET, encode(new TreeSet<>(names)));
    }


    static String encode(Set<String> sortedNames) {
        return JSON.writeValueAsString(new Stored(VERSION, List.copyOf(sortedNames)));
    }


    /**
     * 저장된 JSON 을 숨긴 이름으로 읽는다. 마이그레이션 검사가 옮긴 JSON 을 같은 해석으로 읽으려고 연다.
     *
     * @throws IllegalStateException 모르는 버전이거나 형식이 깨졌을 때
     */
    public static List<String> decode(String value) {
        Stored stored;
        try {
            stored = JSON.readValue(value, Stored.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("stored toolset setting is not readable", ex);
        }
        if (stored.version() == null || stored.version() != VERSION) {
            throw new IllegalStateException("stored toolset setting has an unknown version");
        }
        if (stored.hidden() == null || stored.hidden().stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalStateException("stored toolset setting is incomplete");
        }
        return stored.hidden();
    }


    /** 저장하는 JSON 의 모양이다. 칸 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(Integer version, List<String> hidden) {
    }
}
