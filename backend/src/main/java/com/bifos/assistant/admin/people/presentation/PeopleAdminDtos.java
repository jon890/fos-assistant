package com.bifos.assistant.admin.people.presentation;

import com.bifos.assistant.people.domain.AllowedPerson;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 사람 관리 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PeopleAdminDtos {

    /**
     * 사람 하나를 더하는 요청이다.
     *
     * <p>{@code hermesProfile} 의 규칙은 {@code HermesProfileKeyStore} 의 {@code PROFILE_NAME} 과
     * 같다. 그 규칙을 통과하지 않은 이름은 key 파일 경로를 만들지 못해 profile 을 만들다 실패한다.
     * 여기서 먼저 거절해 반만 만들어진 것을 남기지 않는다.
     */
    public record CreatePersonRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 100) String displayName,

        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String hermesProfile) {
    }

    /**
     * 관리 화면이 보는 한 사람이다.
     *
     * @param joined 그 메일 주소의 {@code app_user} 가 있는가. 로그인 완료 기록만 있고 일반 요청으로
     * 사용자와 기본 에이전트가 아직 만들어지지 않았으면 거짓이다
     */
    public record PersonView(
        Long id,
        String email,
        String displayName,
        String hermesProfile,
        boolean enabled,
        boolean joined,
        Instant lastLoginAt,
        Instant lastConversationAt) {

        static PersonView of(AllowedPerson person, boolean joined, Instant lastConversationAt) {
            return new PersonView(
                person.id(),
                person.email(),
                person.displayName(),
                person.hermesProfile(),
                person.enabled(),
                joined,
                person.lastLoginAt(),
                lastConversationAt);
        }
    }

    /** 들어올 수 있는지를 올리고 내린다. 행을 지우지 않는다. */
    public record UpdatePersonRequest(@NotNull Boolean enabled) {
    }
}
