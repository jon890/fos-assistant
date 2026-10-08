package com.bifos.assistant.people.presentation;

import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.application.model.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.presentation.PeopleDtos.CreatePersonRequest;
import com.bifos.assistant.people.presentation.PeopleDtos.PersonView;
import com.bifos.assistant.people.presentation.PeopleDtos.UpdatePersonRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 그룹에 사람을 더하고 목록을 보는 자리다.
 *
 * <p>{@code ADMIN} 만 부른다. 여기서 더한 사람은 아직 {@code app_user} 가 없고, 그 사람이 처음
 * 로그인할 때 사용자와 에이전트가 함께 생긴다.
 */
@RestController
@RequestMapping("/api/v1/admin/people")
@RequiredArgsConstructor
public class PeopleAdminController {

    private final PersonRegistrar registrar;
    private final CurrentUserProvider currentUser;
    private final PersonAccessService access;

    /** 허용 목록 전체를 준다. */
    @GetMapping
    public List<PersonView> list() {
        currentUser.requireAdmin();
        return access.list().stream()
                .map(entry -> PersonView.of(entry.person(), entry.joined(), entry.lastConversationAt()))
                .toList();
    }

    @PostMapping
    public PersonView create(@Valid @RequestBody CreatePersonRequest request) {
        currentUser.requireAdmin();
        AllowedPerson person = registrar.register(request.email(), request.displayName(), request.hermesProfile());
        // 방금 더한 사람은 아직 로그인하지 않았다. 사용자를 다시 뒤지지 않고 거짓으로 둔다.
        return PersonView.of(person, false, null);
    }

    /**
     * 들어올 수 있는지를 올리고 내린다.
     *
     * <p>내려도 이미 만들어진 에이전트는 그대로 둔다. 그 사람이 남은 토큰으로 대화를 이어갈 수 있다는
     * 뜻이다. 막으려면 그 에이전트도 함께 내려야 한다. 그 사람의 서비스 토큰은 끄는 저장과 한 트랜잭션에서 모두
     * 폐기한다. 폐기가 실패하면 끄기도 되돌아간다. 다시 올려도 되살아나지 않는다(ADR-056).
     */
    @PatchMapping("/{id}")
    public PersonView update(@PathVariable Long id, @Valid @RequestBody UpdatePersonRequest request) {
        currentUser.requireAdmin();
        PersonAccess result = access.setEnabled(id, request.enabled());
        return PersonView.of(result.person(), result.joined(), result.lastConversationAt());
    }
}
