package com.bifos.assistant.people.presentation;

import com.bifos.assistant.people.domain.AllowedPerson;

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
}
