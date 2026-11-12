package com.bifos.assistant.chat.turn.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.chat.room.application.RoomConversations;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기본 비서와 늘 이어지는 대화방을 열고 읽는다(docs/features/agent-team/spec.md 의 REQ8).
 *
 * <p>대화 목록 API 는 대화방을 빼므로 사이드바와 첫 화면이 이 경로로 대화방을 따로 읽는다.
 */
@RestController
@RequestMapping("/api/v1/chat/conversations/room")
@RequiredArgsConstructor
public class RoomController {

    private final RoomConversations rooms;
    private final CurrentUserProvider currentUser;
    private final AgentService agents;


    /** 요청자의 대화방을 찾고 없으면 기본 비서로 만든다. 기본 비서가 없으면 {@code AGENT_NOT_FOUND} 다. */
    @PostMapping
    public ConversationView open() {
        return viewOf(rooms.findOrCreate(currentUser.require()));
    }


    /** 요청자의 대화방을 찾기만 한다. 아직 열지 않았으면 {@code CONVERSATION_NOT_FOUND} 다. */
    @GetMapping
    public ConversationView find() {
        return viewOf(rooms.find(currentUser.require().id())
            .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "the room is not opened yet")));
    }


    private ConversationView viewOf(Conversation room) {
        return ConversationView.of(room, agents.findById(room.agentId()).orElse(null), null);
    }
}
