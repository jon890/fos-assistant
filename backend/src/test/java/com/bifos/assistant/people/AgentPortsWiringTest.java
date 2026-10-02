package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.ProfileProvisioning;
import com.bifos.assistant.agent.application.ReservedProfileNames;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@code agent} 가 둔 port 둘을 {@code people} 의 구현이 채우는지 확인한다(ADR-068). */
@SpringBootTest
@ActiveProfiles("test")
class AgentPortsWiringTest {

    @Autowired
    ProfileProvisioning provisioning;

    @Autowired
    ReservedProfileNames reservedProfileNames;

    @Autowired
    AllowedPersonRepository people;

    private final List<AllowedPerson> saved = new ArrayList<>();

    @AfterEach
    void tearDown() {
        people.deleteAll(saved);
        saved.clear();
    }

    @Test
    @DisplayName("profile 을 만들고 거두는 port 는 HermesProfileProvisioner 가 채운다")
    void wiresProfileProvisioningToHermesProfileProvisioner() {
        assertThat(provisioning).isInstanceOf(HermesProfileProvisioner.class);
    }

    @Test
    @DisplayName("허용 목록에 저장한 profile 이름은 쥐고 있다고 답한다")
    void reportsProfileNameHeldByAllowedPerson() {
        String profile = uniqueProfile();
        saved.add(people.save(AllowedPerson.of(profile + "@example.com", "x", profile, Instant.now())));

        assertThat(reservedProfileNames.reservedByPerson(profile))
                .as("허용 목록에 저장한 profile %s", profile)
                .isTrue();
    }

    @Test
    @DisplayName("허용 목록에 없는 profile 이름은 쥐고 있지 않다고 답한다")
    void reportsProfileNameAbsentFromAllowedPeopleAsFree() {
        String profile = uniqueProfile();

        assertThat(reservedProfileNames.reservedByPerson(profile))
                .as("허용 목록에 없는 profile %s", profile)
                .isFalse();
    }

    private static String uniqueProfile() {
        return "ports-" + UUID.randomUUID();
    }
}
