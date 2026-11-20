package com.bifos.assistant.user.application;

import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 화면에 필요한 사용자 이름 조회를 application 층에서 맡는다. */
@Service
@RequiredArgsConstructor
public class UserDisplayNameService {

    private final AppUserRepository users;


    @Transactional(readOnly = true)
    public String find(Long userId) {
        return users.findById(userId).map(user -> user.displayName()).orElse(null);
    }


    /**
     * 그룹의 모든 사용자 번호와 이름을 번호 순으로 낸다.
     *
     * <p>꺼진 사용자도 넣는다. 그 사람의 실행도 그룹의 기록이라 관리자 요약이 함께 센다. 이름이 없으면 값이 null 이다.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> namesInGroup(Long groupId) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (AppUser user : users.findByGroupIdOrderByIdAsc(groupId)) {
            names.put(user.id(), user.displayName());
        }
        return names;
    }
}
