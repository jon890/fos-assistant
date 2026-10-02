package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.application.ServiceTokenService;
import com.bifos.assistant.memory.application.model.IssuedServiceToken;
import com.bifos.assistant.memory.application.model.ServiceTokenGrant;
import com.bifos.assistant.memory.domain.ServiceToken;
import com.bifos.assistant.memory.infra.ServiceTokenCollectionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenRepository;
import com.bifos.assistant.memory.presentation.MemoryDtos.IssueServiceTokenRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.ServiceTokenGrantBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.ServiceTokenView;
import com.bifos.assistant.memory.presentation.ServiceTokenController;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.application.model.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.people.presentation.PeopleAdminController;
import com.bifos.assistant.people.presentation.PeopleDtos.UpdatePersonRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 서비스 토큰의 발급과 폐기와 만료 규칙, 사용자를 끌 때의 일괄 폐기를 확인한다(ADR-056). */
@SpringBootTest
@ActiveProfiles("test")
class ServiceTokenServiceTest {

    private static final String DAD_EMAIL = "svc-token-dad@example.com";
    private static final String KID_EMAIL = "svc-token-kid@example.com";
    private static final List<ServiceTokenGrant> IDENTITY = List.of(new ServiceTokenGrant("identity", true));

    @Autowired
    ServiceTokenService service;

    @Autowired
    ServiceTokenRepository repository;

    @Autowired
    ServiceTokenCollectionRepository collectionRepository;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PersonAccessService personAccess;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    PersonRegistrar registrar;

    /** 허용 목록에 켜져 있는 사용자다. 발급은 주인이 켜져 있을 때만 받는다. */
    private CurrentUser dad;

    private CurrentUser kid;

    @BeforeEach
    void setUp() {
        clean();
        AppUser saved = users.save(AppUser.of(DAD_EMAIL, "dad", 1L, UserRole.ADMIN, Instant.now()));
        people.save(AllowedPerson.of(DAD_EMAIL, "dad", "svc-token-dad", Instant.now()));
        dad = new CurrentUser(saved.id(), DAD_EMAIL, "dad", 1L, UserRole.ADMIN);
        AppUser savedKid = users.save(AppUser.of(KID_EMAIL, "kid", 1L, UserRole.MEMBER, Instant.now()));
        people.save(AllowedPerson.of(KID_EMAIL, "kid", "svc-token-kid", Instant.now()));
        kid = new CurrentUser(savedKid.id(), KID_EMAIL, "kid", 1L, UserRole.MEMBER);
    }

    @AfterEach
    void clean() {
        collectionRepository.deleteAll();
        repository.deleteAll();
        // 사용자를 끄는 검사가 넣은 줄이 다른 검사의 허용 목록에 남지 않게 한다
        for (String email : List.of(DAD_EMAIL, KID_EMAIL, "dad-person@example.com", "never-signed-in@example.com")) {
            people.findAll().stream()
                    .filter(person -> person.email().equals(email))
                    .forEach(people::delete);
            users.findByEmail(email).ifPresent(users::delete);
        }
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private ServiceToken reload(Long id) {
        return repository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("발급하면 원문은 접두사를 갖고 해시만 저장하며 만료와 collection 이 남는다")
    void issuesAndStoresOnlyHash() {
        IssuedServiceToken issued = service.issue(dad, "career-os", 90, IDENTITY);

        assertThat(issued.rawToken()).startsWith("fos_svc_");
        ServiceToken stored = reload(issued.snapshot().token().id());
        assertThat(stored.tokenHash()).isEqualTo(Sha256.hex(issued.rawToken())).isNotEqualTo(issued.rawToken());
        assertThat(Duration.between(Instant.now().plus(Duration.ofDays(90)), stored.expiresAt())
                        .abs())
                .isLessThan(Duration.ofMinutes(1));
        assertThat(jdbc.queryForList(
                        "SELECT collection, allow_sensitive FROM service_token_collection WHERE token_id = ?",
                        stored.id()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("COLLECTION")).isEqualTo("identity");
                    assertThat(row.get("ALLOW_SENSITIVE")).isEqualTo(true);
                });
    }

    @Test
    @DisplayName("요청의 만료 일수가 없거나 범위 밖이면 검증이 실패하고 90일은 통과한다")
    void requestRequiresExpiryInRange() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        List<ServiceTokenGrantBody> grants = List.of(new ServiceTokenGrantBody("identity", true));

        for (Integer days : new Integer[] {null, 0, 366}) {
            assertThat(validator.validate(new IssueServiceTokenRequest("career-os", days, grants)))
                    .as("expiresInDays=%s", days)
                    .isNotEmpty();
        }
        assertThat(validator.validate(new IssueServiceTokenRequest("career-os", 90, grants)))
                .isEmpty();
    }

    @Test
    @DisplayName("서비스는 만료 일수가 범위 밖이면 거절한다")
    void serviceRejectsOutOfRangeExpiry() {
        assertCode(() -> service.issue(dad, "x", 0, IDENTITY), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.issue(dad, "x", 366, IDENTITY), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("두 번 발급한 원문은 서로 다르다")
    void rawTokensDiffer() {
        assertThat(service.issue(dad, "a", 90, IDENTITY).rawToken())
                .isNotEqualTo(service.issue(dad, "b", 90, IDENTITY).rawToken());
    }

    @Test
    @DisplayName("목록은 요청자의 토큰만 낸다")
    void listsOnlyOwn() {
        service.issue(dad, "a", 90, IDENTITY);

        assertThat(service.listOf(dad)).hasSize(1);
        assertThat(service.listOf(kid)).isEmpty();
    }

    @Test
    @DisplayName("남의 토큰과 없는 토큰의 폐기는 같은 오류이고 남의 토큰은 그대로다")
    void revokeHidesOthersAndMissing() {
        Long id = service.issue(dad, "a", 90, IDENTITY).snapshot().token().id();

        assertCode(() -> service.revoke(kid, id), ErrorCode.SERVICE_TOKEN_NOT_FOUND);
        assertCode(() -> service.revoke(kid, 9_999L), ErrorCode.SERVICE_TOKEN_NOT_FOUND);
        assertThat(reload(id).revokedAt()).isNull();
    }

    @Test
    @DisplayName("두 번 폐기해도 처음 폐기 시각이 그대로다")
    void revokeIsIdempotent() {
        Long id = service.issue(dad, "a", 90, IDENTITY).snapshot().token().id();

        service.revoke(dad, id);
        Instant first = reload(id).revokedAt();
        service.revoke(dad, id);

        assertThat(first).isNotNull();
        assertThat(reload(id).revokedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("collection 이 비었거나 없거나 겹치면 거절한다")
    void rejectsBadCollections() {
        assertCode(() -> service.issue(dad, "x", 90, List.of()), ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> service.issue(dad, "x", 90, List.of(new ServiceTokenGrant("no-such-area", false))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> service.issue(
                        dad,
                        "x",
                        90,
                        List.of(new ServiceTokenGrant("identity", false), new ServiceTokenGrant("identity", true))),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("사용자를 끈 사건은 그 사용자의 남은 토큰만 폐기한다")
    void accessRevokedRevokesOnlyOwnLiveTokens() {
        Long first = service.issue(dad, "a", 90, IDENTITY).snapshot().token().id();
        Long second = service.issue(dad, "b", 90, IDENTITY).snapshot().token().id();
        Long kids = service.issue(kid, "c", 90, IDENTITY).snapshot().token().id();
        service.revoke(dad, first);
        Instant firstRevokedAt = reload(first).revokedAt();

        events.publishEvent(new UserAccessRevoked(dad.id()));

        assertThat(reload(second).revokedAt()).isNotNull();
        assertThat(reload(first).revokedAt()).isEqualTo(firstRevokedAt);
        assertThat(reload(kids).revokedAt()).isNull();
    }

    @Test
    @DisplayName("허용 목록에서 사용자를 끄면 토큰이 폐기되고 다시 켜도 되살아나지 않는다")
    void disablingPersonRevokesAndEnablingDoesNotRestore() {
        AppUser dad = users.save(AppUser.of("dad-person@example.com", "dad", 1L, UserRole.ADMIN, Instant.now()));
        AllowedPerson person = people.save(AllowedPerson.of("dad-person@example.com", "dad", "dad", Instant.now()));
        CurrentUser owner = new CurrentUser(dad.id(), dad.email(), "dad", 1L, UserRole.ADMIN);
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(owner);
        PeopleAdminController admin = new PeopleAdminController(registrar, provider, personAccess);
        Long id = service.issue(owner, "a", 90, IDENTITY).snapshot().token().id();

        admin.update(person.id(), new UpdatePersonRequest(false));
        Instant revokedAt = reload(id).revokedAt();
        admin.update(person.id(), new UpdatePersonRequest(true));

        assertThat(revokedAt).isNotNull();
        assertThat(reload(id).revokedAt()).isEqualTo(revokedAt);
    }

    @Test
    @DisplayName("app_user 가 없는 사람을 꺼도 예외가 없다")
    void disablingPersonWithoutUserIsHarmless() {
        AllowedPerson person = people.save(AllowedPerson.of("never-signed-in@example.com", "x", "x", Instant.now()));
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        PeopleAdminController admin = new PeopleAdminController(registrar, provider, personAccess);

        admin.update(person.id(), new UpdatePersonRequest(false));

        assertThat(people.findById(person.id()).orElseThrow().enabled()).isFalse();
    }

    @Test
    @DisplayName("허용 목록에서 꺼진 사용자는 살아 있는 세션으로도 토큰을 발급받지 못한다")
    void disabledOwnerCannotIssue() {
        AppUser user = users.save(AppUser.of("dad-person@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        AllowedPerson person = people.save(AllowedPerson.of("dad-person@example.com", "dad", "dad", Instant.now()));
        CurrentUser owner = new CurrentUser(user.id(), user.email(), "dad", 1L, UserRole.MEMBER);
        personAccess.setEnabled(person.id(), false);

        assertCode(() -> service.issue(owner, "a", 90, IDENTITY), ErrorCode.FORBIDDEN);
        personAccess.setEnabled(person.id(), true);

        assertThat(repository.findByUserIdOrderByIdDesc(user.id())).isEmpty();
    }

    @Test
    @DisplayName("허용 목록 줄이 없는 사용자는 토큰을 발급받지 못한다")
    void ownerWithoutAllowListRowCannotIssue() {
        AppUser user = users.save(AppUser.of("never-signed-in@example.com", "x", 1L, UserRole.MEMBER, Instant.now()));
        CurrentUser owner = new CurrentUser(user.id(), user.email(), "x", 1L, UserRole.MEMBER);

        assertCode(() -> service.issue(owner, "a", 90, IDENTITY), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("app_user 의 메일 주소가 원문(대소문자 다름)이어도 끄면 토큰이 폐기된다")
    void disablingMatchesUserWithDifferentCase() {
        AppUser user = users.save(AppUser.of("Dad-Person@Example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        AllowedPerson person = people.save(AllowedPerson.of("dad-person@example.com", "dad", "dad", Instant.now()));
        CurrentUser owner = new CurrentUser(user.id(), user.email(), "dad", 1L, UserRole.MEMBER);
        Long id = service.issue(owner, "a", 90, IDENTITY).snapshot().token().id();

        PersonAccess result = personAccess.setEnabled(person.id(), false);

        assertThat(result.joined()).isTrue();
        assertThat(reload(id).revokedAt()).isNotNull();
    }

    @Test
    @DisplayName("인증이 토큰을 읽은 뒤 폐기가 먼저 커밋돼도 사용 시각을 적는 갱신이 폐기를 되돌리지 않는다")
    void markUsedDoesNotUndoConcurrentRevoke() {
        Long id = service.issue(dad, "a", 90, IDENTITY).snapshot().token().id();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            // 인증이 토큰을 읽어 영속성 컨텍스트에 올린 상태다
            repository.findById(id).orElseThrow();
            inner.executeWithoutResult(other -> service.revoke(dad, id));
            repository.markUsed(id, Instant.now());
        });

        assertThat(reload(id).revokedAt()).isNotNull();
        assertThat(reload(id).lastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("목록 응답을 JSON 으로 바꾼 글에 원문과 해시가 없다")
    void listResponseHasNoRawTokenOrHash() throws Exception {
        IssuedServiceToken issued = service.issue(dad, "a", 90, IDENTITY);
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(dad);
        List<ServiceTokenView> views = new ServiceTokenController(service, provider).list();

        String json = new JsonMapper().writeValueAsString(views);

        assertThat(json).doesNotContain(issued.rawToken()).doesNotContain(Sha256.hex(issued.rawToken()));
        assertThat(json).doesNotContain("tokenHash");
        assertThat(views).hasSize(1);
    }
}
