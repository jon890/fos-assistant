package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpProbe;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 관리자가 실제 {@link PersonAccessService} 로 사용자를 끄면, 커밋 뒤 리스너가 새 트랜잭션에서 그 사용자의 브라우저를 멈추는지 본다.
 *
 * <p>리스너는 커밋이 끝난 트랜잭션의 정리 단계에서 돈다. 그 자리에서 저장이 원래 트랜잭션에 붙으면 저장이 반영되지 않으므로, 기능을 켠 실제 빈으로
 * 확인한다.
 */
@SpringBootTest(properties = "assistant.browser.enabled=true")
@ActiveProfiles("test")
@Import(UserBrowserAccessRevokedTest.FakeRuntime.class)
class UserBrowserAccessRevokedTest {

    private static final String EMAIL = "browser-revoked@example.com";

    /** 실제 proxy 와 CDP 대신 메모리 대역을 쓴다. */
    @TestConfiguration
    static class FakeRuntime {
        @Bean
        @Primary
        FakeBrowserRuntime fakeBrowserRuntime() {
            return new FakeBrowserRuntime();
        }

        @Bean
        @Primary
        CdpProbe fakeCdpProbe() {
            return address -> true;
        }
    }

    @Autowired
    PersonAccessService access;

    @Autowired
    UserBrowserService browsers;

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    BrowserRuntime runtime;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM user_browser");
        people.findAll().stream().filter(p -> p.email().equals(EMAIL)).forEach(people::delete);
        users.findAllByNormalizedEmail(EMAIL).forEach(users::delete);
    }

    @Test
    @DisplayName("사용자를 끄면 그 사용자의 켜진 브라우저가 STOPPED 가 되고 컨테이너가 지워진다")
    void stopsBrowserWhenAccessRevoked() {
        AllowedPerson person = people.save(AllowedPerson.of(EMAIL, "x", "browser-revoked", Instant.now()));
        AppUser user = users.save(AppUser.of(EMAIL, "x", 1L, UserRole.MEMBER, Instant.now()));
        browsers.create(user.id());
        assertThat(browsers.start(user.id()).status()).isEqualTo(UserBrowserStatus.RUNNING);

        access.setEnabled(person.id(), false);

        assertThat(repository.findByUserId(user.id()).orElseThrow().status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(((FakeBrowserRuntime) runtime).containers).isEmpty();
    }
}
