package com.bifos.assistant.proactive;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 점검 대화의 발견 반응 단추가 쓰던 두 경로가 없는지 본다({@code docs/features/proactive/spec.md} 의 REQ3). 반응은 지금 화면의 제안
 * 카드와 할 일 목록에서만 받는다.
 */
@BackendIntegrationTest
class RemovedCheckFindingRoutesTest {

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }


    @Test
    @DisplayName("점검 대화의 발견 목록 경로는 404 다")
    void findingListRouteIsGone() throws Exception {
        mvc.perform(get("/api/v1/chat/conversations/{id}/check-findings", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }


    @Test
    @DisplayName("발견 반응 경로는 404 다")
    void findingReactionRouteIsGone() throws Exception {
        mvc.perform(put("/api/v1/check-findings/{id}/reaction", 1L)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reaction\":\"DISMISSED\"}"))
            .andExpect(status().isNotFound());
    }
}
