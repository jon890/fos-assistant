package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.TaskLabel;
import java.util.Collection;
import java.util.Map;

/**
 * 대화를 만든 예약 작업의 이름을 알려 준다(ADR-078).
 *
 * <p>{@code chat} 은 작업을 모른다. 구현은 작업 쪽이 갖고, 대화 목록은 이 port 로 작업 이름을 읽는다. 구현이 없어도 대화
 * 목록은 돈다. 그때는 작업 칸이 비어 있다.
 */
public interface ConversationTaskLabels {

    /**
     * 작업 번호마다 공개 식별자와 이름이다. 지운 작업도 돌려준다. 없는 번호는 빠진다.
     *
     * @param taskIds 대화의 {@code task_id} 들
     */
    Map<Long, TaskLabel> labelsOf(Collection<Long> taskIds);
}
