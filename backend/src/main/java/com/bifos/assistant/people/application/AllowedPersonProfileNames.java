package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.application.ReservedProfileNames;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 허용 목록이 쥔 profile 이름을 {@code agent} 에 알려 준다.
 *
 * <p>트랜잭션을 따로 열지 않는다. 에이전트를 만드는 쪽의 트랜잭션 안에서 돈다.
 */
@Service
@RequiredArgsConstructor
public class AllowedPersonProfileNames implements ReservedProfileNames {

    private final AllowedPersonRepository allowedPeople;

    @Override
    public boolean reservedByPerson(String profileName) {
        return allowedPeople.existsByHermesProfile(profileName);
    }
}
