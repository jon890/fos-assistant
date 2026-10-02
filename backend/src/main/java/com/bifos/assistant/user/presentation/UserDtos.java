package com.bifos.assistant.user.presentation;

import com.bifos.assistant.shared.auth.CurrentUser;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 로그인한 사용자 자신을 보이는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 화면에 나가는 모양은 여기 둔다. 같은 저장소의 {@code ChatDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserDtos {

    public record MeView(Long id, String email, String displayName, String role) {
        static MeView from(CurrentUser user) {
            return new MeView(
                    user.id(), user.email(), user.displayName(), user.role().name());
        }
    }
}
