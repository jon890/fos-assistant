package com.bifos.assistant.people.application;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메일 주소 하나가 들어와도 되는지 판정한다.
 *
 * <p>판정만 하고 아무것도 만들지 않는다. 이 판정은 아직 아무 사용자도 없는 시점에 돌기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class SignInPolicy {

    private final AllowedPersonRepository people;

    /** 들어와도 되면 그 사람을, 아니면 비어 있는 값을 돌려준다. */
    @Transactional(readOnly = true)
    public Optional<AllowedPerson> admit(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return people.findByEmailAndEnabledTrue(AllowedPerson.normalizeEmail(email));
    }
}
