package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemorySources;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.memory.presentation.MemoryLinkController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 대체 제안의 목록과 받아들이기, 거절, 되돌리기 API 와 기억 목록의 「대체됨」 칸을 확인한다(ADR-20261015 / memory-link). */
@BackendIntegrationTest
class MemoryLinkControllerTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "주인", 10L, UserRole.ADMIN);
    private static final CurrentUser OTHER = new CurrentUser(2L, "other@example.com", "다른 사람", 10L, UserRole.MEMBER);

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    ContextAssembler context;

    @Autowired
    MemorySources sources;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;


    @BeforeEach
    void setUp() {
        links.deleteAll();
        repository.deleteAll();
        when(currentUser.require()).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(
            new MemoryLinkController(linkService, currentUser),
            new MemoryController(memories, context, sources, linkService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }


    @Test
    @DisplayName("대체 제안을 두 제목과 함께 보이고 받아들이면 옛 항목이 목록에 대체됨으로 남고 되돌리면 칸이 빈다")
    void listsAcceptsAndRevokesSupersede() throws Exception {
        Memory old = memories.create(OWNER, MemoryScope.USER, "옛 주소", "서울", false);
        Memory fresh = memories.create(OWNER, MemoryScope.USER, "새 주소", "부산", false);
        MemoryLink link = linkService.proposeSupersede(OWNER, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 7L);

        JsonNode pending = body(mvc.perform(get("/api/v1/memory-links/pending")).andReturn().getResponse(), 200);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("id").asLong()).isEqualTo(link.id());
        assertThat(pending.get(0).path("newMemory").path("title").asString()).isEqualTo("새 주소");
        assertThat(pending.get(0).path("oldMemory").path("title").asString()).isEqualTo("옛 주소");
        assertThat(pending.get(0).path("origin").asString()).isEqualTo("AGENT");
        assertThat(pending.get(0).toString()).doesNotContain("서울", "부산");

        JsonNode accepted = body(post("/api/v1/memory-links/" + link.id() + "/accept"), 200);

        assertThat(accepted.path("status").asString()).isEqualTo("ACTIVE");
        JsonNode oldView = viewOf(old.id());
        assertThat(oldView.path("supersededBy").path("linkId").asLong()).isEqualTo(link.id());
        assertThat(oldView.path("supersededBy").path("title").asString()).isEqualTo("새 주소");
        assertThat(viewOf(fresh.id()).path("supersededBy").isNull()).isTrue();
        assertThat(body(mvc.perform(get("/api/v1/memory-links/pending")).andReturn().getResponse(), 200)).isEmpty();

        JsonNode revoked = body(post("/api/v1/memory-links/" + link.id() + "/revoke"), 200);

        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        assertThat(viewOf(old.id()).path("supersededBy").isNull()).isTrue();
    }


    @Test
    @DisplayName("거절하면 끊긴 줄이 되고 이미 정한 줄을 다시 받아들이면 409 다")
    void rejectsAndConflictsOnSecondDecision() throws Exception {
        Memory old = memories.create(OWNER, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(OWNER, MemoryScope.USER, "새", "2", false);
        MemoryLink link = linkService.proposeSupersede(OWNER, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);

        assertThat(body(post("/api/v1/memory-links/" + link.id() + "/reject"), 200).path("status").asString())
            .isEqualTo("REVOKED");
        assertThat(body(post("/api/v1/memory-links/" + link.id() + "/accept"), 409).path("code").asString())
            .isEqualTo("MEMORY_LINK_STATE_CONFLICT");
        assertThat(body(post("/api/v1/memory-links/" + link.id() + "/revoke"), 409).path("code").asString())
            .isEqualTo("MEMORY_LINK_STATE_CONFLICT");
    }


    @Test
    @DisplayName("남의 줄과 없는 줄은 같은 404 이고 그룹 항목의 대체를 MEMBER 가 정하면 403 이다")
    void hidesOthersLinksAndForbidsMembers() throws Exception {
        Memory old = memories.create(OWNER, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(OWNER, MemoryScope.USER, "새", "2", false);
        MemoryLink personal = linkService.proposeSupersede(OWNER, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        Memory groupOld = memories.create(OWNER, MemoryScope.GROUP, "그룹 옛", "3", false);
        Memory groupNew = memories.create(OWNER, MemoryScope.GROUP, "그룹 새", "4", false);
        MemoryLink group = linkService.proposeSupersede(OWNER, groupNew.id(), groupOld.id(), MemoryLinkOrigin.AGENT, null);
        when(currentUser.require()).thenReturn(OTHER);

        JsonNode foreign = body(post("/api/v1/memory-links/" + personal.id() + "/accept"), 404);
        JsonNode missing = body(post("/api/v1/memory-links/999999999/accept"), 404);

        assertThat(foreign.path("code").asString()).isEqualTo("MEMORY_NOT_FOUND");
        assertThat(missing.path("code").asString()).isEqualTo(foreign.path("code").asString());
        assertThat(body(post("/api/v1/memory-links/" + group.id() + "/accept"), 403).path("code").asString())
            .isEqualTo("FORBIDDEN");
        assertThat(body(mvc.perform(get("/api/v1/memory-links/pending")).andReturn().getResponse(), 200)).isEmpty();
    }


    private JsonNode viewOf(Long memoryId) throws Exception {
        JsonNode list = body(mvc.perform(get("/api/v1/memories")).andReturn().getResponse(), 200);
        for (JsonNode view : list) {
            if (view.path("id").asLong() == memoryId) {
                return view;
            }
        }
        throw new AssertionError("목록에 없는 기억: " + memoryId);
    }


    private JsonNode body(MockHttpServletRequestBuilder request, int status)
        throws Exception {
        return body(mvc.perform(request).andReturn().getResponse(), status);
    }


    private JsonNode body(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as("본문: %s", response.getContentAsString(StandardCharsets.UTF_8))
            .isEqualTo(status);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
