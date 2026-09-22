package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.PersonaService;
import com.bifos.assistant.agent.presentation.AgentDtos.PersonaView;
import com.bifos.assistant.agent.presentation.AgentDtos.WritePersonaRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트의 성격을 읽고 쓰는 경로다.
 *
 * <p>볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다. 누가 고칠 수 있는지와 부르는 순서는
 * {@link PersonaService} 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentPersonaController {

    private final PersonaService personas;
    private final CurrentUserProvider currentUser;

    @GetMapping("/{code}/persona")
    public PersonaView read(@PathVariable String code) {
        return PersonaView.from(personas.read(currentUser.require(), code));
    }

    @PutMapping("/{code}/persona")
    public PersonaView write(@PathVariable String code, @Valid @RequestBody WritePersonaRequest request) {
        return PersonaView.from(
                personas.write(currentUser.require(), code, request.body(), request.baseHash()));
    }
}
