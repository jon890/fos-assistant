package com.bifos.assistant.user.application;

import com.bifos.assistant.user.infra.AppUserRepository;
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
}
