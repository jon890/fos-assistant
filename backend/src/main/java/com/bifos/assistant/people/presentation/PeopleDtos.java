package com.bifos.assistant.people.presentation;

import com.bifos.assistant.people.domain.AllowedPerson;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 사람을 다루는 경로의 요청과 응답 모양이다.
 *
 * <p>컨트롤러는 경로와 권한과 흐름만 맡고 모양은 여기 둔다. 같은 저장소의 {@code UserDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
public final class PeopleDtos {

    private PeopleDtos() {
    }

    /**
     * 들어와도 되는지 묻는 주소다.
     *
     * <p>빈 값에 검사 제약을 걸지 않는다. 걸면 본문 검사가 토큰 검사보다 먼저 돌아, 토큰이 없는 요청이
     * 401 대신 400 을 받는다. 빈 주소는 허용 목록에 없는 주소와 같은 답을 받는다.
     */
    public record SignInCheckRequest(String email) {
    }

    /**
     * 들어와도 되는지에 대한 답이다.
     *
     * <p>거절할 때 {@code displayName} 과 {@code hermesProfile} 은 비어 있다. 누구인지 알려 주지 않는다.
     */
    public record SignInCheckView(boolean allowed, String displayName, String hermesProfile) {

        static SignInCheckView of(AllowedPerson person) {
            return new SignInCheckView(true, person.displayName(), person.hermesProfile());
        }

        static SignInCheckView rejected() {
            return new SignInCheckView(false, null, null);
        }
    }

    /**
     * 관리 화면이 보는 한 사람이다.
     *
     * @param joined 그 메일 주소의 {@code app_user} 가 있는가. 거짓이면 아직 한 번도 들어오지 않은
     *     사람이고, 그 사람의 에이전트도 아직 없다
     */
    public record PersonView(
            Long id,
            String email,
            String displayName,
            String hermesProfile,
            boolean enabled,
            boolean joined) {

        static PersonView of(AllowedPerson person, boolean joined) {
            return new PersonView(
                    person.id(),
                    person.email(),
                    person.displayName(),
                    person.hermesProfile(),
                    person.isEnabled(),
                    joined);
        }
    }

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

    /** 들어올 수 있는지를 올리고 내린다. 행을 지우지 않는다. */
    public record UpdatePersonRequest(@NotNull Boolean enabled) {
    }
}
