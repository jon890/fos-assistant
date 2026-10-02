package com.bifos.assistant.memory.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Set;

/**
 * 지금 조립하면 상한 때문에 실리지 않을 Memory 의 번호다.
 *
 * <p>조립 규칙을 아는 {@code context} 가 구현한다.
 */
public interface OmittedMemories {
    Set<Long> omittedFor(CurrentUser user);
}
