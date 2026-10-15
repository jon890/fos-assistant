package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemoryRememberOutcome;
import com.bifos.assistant.memory.application.model.MemoryRememberRequest;
import com.bifos.assistant.memory.application.model.MemoryRememberResult;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;

/** 중복 후보 조회와 잠금 사이에 커밋된 사용자 수정을 H2와 MySQL에서 보존한다. */
@BackendIntegrationTest
class MemoryCaptureConcurrencyTest {
    private static final CurrentUser USER = new CurrentUser(1L, "memory@example.com", "사용자", 10L, UserRole.ADMIN);
    private static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"), Set.of());
    private static final String ORIGINAL = "산책은 아침에 한다";
    private static final String CHANGED = "산책은 저녁에 한다";

    @Autowired
    MemoryService service;
    @Autowired
    MemoryCaptureService captureService;
    @Autowired
    MemoryRepository memories;
    @Autowired
    MemoryRevisionRepository revisions;
    @Autowired
    MemoryCaptureRepository captures;


    @BeforeEach
    void setUp() {
        captures.deleteAll();
        revisions.deleteAll();
        memories.deleteAll();
    }


    @Test
    @DisplayName("기존 제안 수락은 동시에 고친 retrieval과 판을 보존하고 그 판으로 되돌린다")
    void preservesConcurrentRetrievalEditWhenAcceptingExistingProposal() throws Exception {
        Memory proposal = service.proposeUser(USER, "산책 시간", ORIGINAL, 901L);

        MemoryRememberResult result = rememberAfterEdit(proposal, ORIGINAL);

        assertThat(result.outcome()).isEqualTo(MemoryRememberOutcome.REMEMBERED);
        assertThat(result.memoryId()).isEqualTo(proposal.id());
        assertThat(memories.count()).isEqualTo(1);
        assertCurrent(proposal.id(), ORIGINAL, MemoryStatus.ACCEPTED, 2);
        assertHistory(proposal.id(), ORIGINAL);
        MemoryCapture capture = captures.findAll().getFirst();
        assertThat(capture.baseRevision()).isEqualTo(2);
        assertThat(capture.previousStatus()).isEqualTo(MemoryStatus.PROPOSED);

        captureService.undo(USER, capture.id());
        assertCurrent(proposal.id(), ORIGINAL, MemoryStatus.PROPOSED, 2);
        assertThat(captures.findById(capture.id()).orElseThrow().undone()).isTrue();
        service.update(USER, proposal.id(), CHANGED, true);
        assertCurrent(proposal.id(), CHANGED, MemoryStatus.PROPOSED, 3);
        assertHistory(proposal.id(), ORIGINAL, ORIGINAL);
    }


    @Test
    @DisplayName("중복 후보의 본문이 바뀌면 원래 제안을 보존하고 새 항목만 되돌린다")
    void preservesOriginalProposalWhenConcurrentEditChangesDedupKey() throws Exception {
        Memory proposal = service.proposeUser(USER, "산책 시간", ORIGINAL, 902L);

        MemoryRememberResult result = rememberAfterEdit(proposal, CHANGED);

        assertThat(result.outcome()).isEqualTo(MemoryRememberOutcome.REMEMBERED);
        assertThat(result.memoryId()).isNotEqualTo(proposal.id());
        assertThat(memories.count()).isEqualTo(2);
        assertCurrent(proposal.id(), CHANGED, MemoryStatus.PROPOSED, 2);
        assertHistory(proposal.id(), ORIGINAL);
        MemoryCapture capture = captures.findAll().getFirst();
        assertThat(capture.baseRevision()).isEqualTo(1);
        assertThat(capture.previousStatus()).isNull();

        captureService.undo(USER, capture.id());
        assertThat(memories.findById(result.memoryId())).isEmpty();
        assertCurrent(proposal.id(), CHANGED, MemoryStatus.PROPOSED, 2);
        service.update(USER, proposal.id(), "산책은 점심에 한다", true);
        assertCurrent(proposal.id(), "산책은 점심에 한다", MemoryStatus.PROPOSED, 3);
        assertHistory(proposal.id(), ORIGINAL, CHANGED);
    }


    private MemoryRememberResult rememberAfterEdit(Memory proposal, String editedContent) throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        Answer<?> repositoryAnswer = mockingDetails(memories).getMockCreationSettings().getDefaultAnswer();
        Answer<Object> pauseAfterRead = invocation -> {
            Object found = repositoryAnswer.answer(invocation);
            read.countDown();
            assertThat(committed.await(20, TimeUnit.SECONDS)).isTrue();
            return found;
        };
        // 수정 전 전체 엔티티 조회와 수정 후 번호 조회에 같은 순서를 적용한다.
        doAnswer(pauseAfterRead).when(memories).findByProposalDedupKey(anyString());
        doAnswer(pauseAfterRead).when(memories).findIdByProposalDedupKey(anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MemoryRememberResult> remembering = pool.submit(() -> captureService.remember(
                USER, CORE, new MemoryRememberRequest("산책 시간", ORIGINAL, null,
                    MemorySensitivity.NORMAL, null, 903L, null, true)));
            if (!read.await(20, TimeUnit.SECONDS)) {
                remembering.get(1, TimeUnit.SECONDS);
                throw new AssertionError("중복 후보 조회에 도달하지 않았다");
            }
            // 이 서비스 호출이 반환하면 별도 트랜잭션의 수정이 커밋된 상태다.
            service.update(USER, proposal.id(), editedContent, true);
            committed.countDown();
            return remembering.get(20, TimeUnit.SECONDS);
        } finally {
            committed.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }


    private void assertCurrent(Long id, String content, MemoryStatus status, int revision) {
        Memory current = memories.findById(id).orElseThrow();
        assertThat(current.content()).isEqualTo(content);
        assertThat(current.retrieval()).isEqualTo(MemoryRetrieval.ALWAYS);
        assertThat(current.status()).isEqualTo(status);
        assertThat(current.revision()).isEqualTo(revision);
    }


    private void assertHistory(Long id, String... contents) {
        assertThat(service.revisionsOf(USER, id)).extracting(MemoryRevision::content).containsExactly(contents);
        assertThat(revisions.findByIdMemoryIdOrderByIdRevisionAsc(id))
            .extracting(revision -> revision.id().revision())
            .containsExactly(IntStream.rangeClosed(1, contents.length).boxed().toArray(Integer[]::new));
    }
}
