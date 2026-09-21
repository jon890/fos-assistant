package com.bifos.assistant.people.presentation;

import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.people.presentation.PeopleDtos.CreatePersonRequest;
import com.bifos.assistant.people.presentation.PeopleDtos.PersonView;
import com.bifos.assistant.people.presentation.PeopleDtos.UpdatePersonRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가족을 더하고 목록을 보는 자리다.
 *
 * <p>{@code ADMIN} 만 부른다. 여기서 더한 사람은 아직 {@code app_user} 가 없고, 그 사람이 처음
 * 로그인할 때 사용자와 에이전트가 함께 생긴다.
 */
@RestController
@RequestMapping("/api/v1/admin/people")
@RequiredArgsConstructor
public class PeopleAdminController {

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final PersonRegistrar registrar;
    private final CurrentUserProvider currentUser;

    /**
     * 허용 목록 전체를 준다.
     *
     * <p>들어온 적이 있는지는 {@code app_user} 의 메일 주소를 한 번에 읽어 맞춘다. 사람마다 따로 묻지
     * 않는 것은 가족 수만큼 질의가 늘어나기 때문이다.
     */
    @GetMapping
    public List<PersonView> list() {
        currentUser.requireAdmin();
        Set<String> joined = users.findAll().stream()
                .map(AppUser::email)
                .map(AllowedPerson::normalizeEmail)
                .collect(Collectors.toSet());
        return people.findAll().stream()
                .map(person -> PersonView.of(person, joined.contains(person.email())))
                .toList();
    }

    @PostMapping
    public PersonView create(@Valid @RequestBody CreatePersonRequest request) {
        currentUser.requireAdmin();
        AllowedPerson person =
                registrar.register(request.email(), request.displayName(), request.hermesProfile());
        // 방금 더한 사람은 아직 로그인하지 않았다. 사용자를 다시 뒤지지 않고 거짓으로 둔다.
        return PersonView.of(person, false);
    }

    /**
     * 들어올 수 있는지를 올리고 내린다.
     *
     * <p>내려도 이미 만들어진 에이전트는 그대로 둔다. 그 사람이 남은 토큰으로 대화를 이어갈 수 있다는
     * 뜻이다. 막으려면 그 에이전트도 함께 내려야 한다.
     */
    @PatchMapping("/{id}")
    public PersonView update(@PathVariable Long id, @Valid @RequestBody UpdatePersonRequest request) {
        currentUser.requireAdmin();
        AllowedPerson person = people.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PERSON_NOT_FOUND, "no such person"));
        if (request.enabled()) {
            person.enable();
        } else {
            person.disable();
        }
        AllowedPerson saved = people.save(person);
        return PersonView.of(saved, users.findByEmail(saved.email()).isPresent());
    }
}
