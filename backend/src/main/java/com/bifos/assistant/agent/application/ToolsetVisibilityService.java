package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.ToolsetHidden;
import com.bifos.assistant.agent.infra.ToolsetHiddenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 숨김 목록만 저장한다. 숨기거나 다시 보여도 Hermes의 활성 도구는 바꾸지 않는다. */
@Service
@RequiredArgsConstructor
public class ToolsetVisibilityService {
    private final ToolsetHiddenRepository hidden;

    @Transactional(readOnly = true)
    public Set<String> hiddenFor(Long groupId) {
        return hidden.findByGroupIdOrderByNameAsc(groupId).stream()
                .map(ToolsetHidden::name)
                .collect(Collectors.toSet());
    }

    @Transactional
    public void save(CurrentUser user, List<String> names) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only admins can hide toolsets");
        }
        if (names == null || names.stream().anyMatch(name -> name == null || !AgentToolPolicy.isKnown(name))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown hidden toolset");
        }
        hidden.deleteByGroupId(user.groupId());
        hidden.flush();
        hidden.saveAll(names.stream()
                .distinct()
                .map(name -> ToolsetHidden.of(user.groupId(), name))
                .toList());
    }
}
