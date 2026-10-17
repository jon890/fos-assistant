package com.bifos.assistant.connector;

import com.bifos.assistant.AssistantApplication;
import com.bifos.assistant.connector.application.ContentDraftWorkerStatusDecoder;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bifos.assistant.chat.domain.ContentDraftException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.bifos.assistant.chat.ContentDraftJobFixture;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Spring RANDOM_PORT의 실제 authorize HTTP와 분리된 Bun worker·CDP 마우스/파일 명령을 연결한다. */
class ContentDraftJobLoopbackTest extends ContentDraftJobFixture {
    private final JsonMapper loopbackJson = JsonMapper.builder().build();


    @Override
    protected void prepareSavePhotos() {
        useDistinctSecondPhoto();
    }


    @Test
    @DisplayName("시작 응답 유실 뒤 같은 job의 terminal을 조회하고 실제 worker·클릭은 각각 한 번이다")
    void resolvesLostStartResponseUsingSameJob() throws Exception {
        exercise("normal", ContentDraftJobStatus.SUCCEEDED, 1, 2, false);
    }


    @Test
    @DisplayName("사진 바이트 교체는 업로드와 저장 클릭 없이 실패하고 같은 snapshot을 다시 시작하지 않는다")
    void rejectsReplacedPhotoBeforeUploadingOrClicking() throws Exception {
        exercise("replace_photo", ContentDraftJobStatus.FAILED, 0, 0, false);
    }


    @Test
    @DisplayName("실제 사진 업로드 뒤 권한 철회가 커밋되면 save HTTP 허가가 거절되어 클릭은 0회다")
    void blocksClickAfterCommittedRevocation() throws Exception {
        exercise("wait_save", ContentDraftJobStatus.SAVING, 0, 2, true);
    }


    @Test
    @DisplayName("클릭 직전 중단은 UNKNOWN이며 실제 클릭 카운터는 0회다")
    void recordsUnknownForInterruptionBeforeClick() throws Exception {
        exercise("before_click", ContentDraftJobStatus.UNKNOWN, 0, 2, false);
    }


    @Test
    @DisplayName("클릭 직후 중단은 UNKNOWN이며 실제 클릭 카운터는 1회다")
    void recordsUnknownForInterruptionAfterClick() throws Exception {
        exercise("after_click", ContentDraftJobStatus.UNKNOWN, 1, 2, false);
    }


    @Test
    @DisplayName("CP ApplicationContext를 종료·재생성해도 DB의 start claim과 예약 ID를 보존하고 같은 worker가 저장한다")
    void resumesSameWorkerAfterControlPlaneContextRestart() throws Exception {
        exercise("wait_save", ContentDraftJobStatus.SUCCEEDED, 1, 2, false, true);
    }


    private void exercise(String scenario, ContentDraftJobStatus expected, int clicks, int uploads, boolean revoke) throws Exception {
        exercise(scenario, expected, clicks, uploads, revoke, false);
    }


    private void exercise(String scenario, ContentDraftJobStatus expected, int clicks, int uploads, boolean revoke, boolean restart) throws Exception {
        var user = loopbackOwner();
        var conversationId = loopbackConversationId();
        UUID snapshotId = preview();
        var request = saves.requestSave(user, origin.id(), snapshotId);
        try (var process = new FixtureProcess(); var cp = restart ? new RestartCp() : null) {
            starter.answer(call -> {
                try {
                    var snapshot = snapshotRows.findByPublicIdAndOwnerUserId(snapshotId, user.id()).orElseThrow();
                    var fields = new ContentDraftSaveTicketPayload(call.jobId(), snapshotId, snapshot.snapshotHash(), request.actionId(), user.id(),
                        conversationId, snapshot.bindingId(), snapshot.connectionId(), snapshot.bindingUpdatedAt(), snapshot.connectionUpdatedAt(), null, null);
                    var stamp = ContentDraftWorkerStatusDecoder.stamp(call.profile(), fields);
                    var args = loopbackJson.createObjectNode().set("snapshot", call.snapshot()).put("jobId", call.jobId().toString())
                        .put("ticket", call.ticket()).set("stamp", loopbackJson.valueToTree(stamp));
                    var command = loopbackJson.createObjectNode().put("operation", "start").put("scenario", scenario)
                        .put("authorizeUrl", authorizeUrl(cp == null ? port : cp.port())).set("args", args).set("photos", loopbackJson.valueToTree(loopbackPhotos(call.snapshot())));
                    assertThat(process.call(command).path("job_id").stringValue()).isEqualTo(call.jobId().toString());
                } catch (Exception ex) {
                    throw new IllegalStateException("합성 worker 시작 실패", ex);
                }
                // 원격 접수 뒤 응답 유실. 승인 경로는 이 예외 뒤 시작을 재전송하지 않는다.
                throw new IllegalStateException("합성 시작 응답 유실");
            });
            assertThat(contentApproval.approve(user, request.actionId(), null).status()).isEqualTo(ActionStatus.UNKNOWN);
            if (revoke || restart) {
                assertThat(process.call(command("wait_save")).path("waiting").booleanValue()).isTrue();
                if (revoke) {
                    personAccess.setEnabled(person.id(), false);
                } else {
                    var jobId = starter.started().getFirst().jobId().toString();
                    var claim = jobRows.findByJobId(jobId).orElseThrow().startClaimedAt();
                    assertThat(claim).isNotNull();
                    cp.restart();
                    assertThat(jobRows.findByJobId(jobId).orElseThrow().startClaimedAt()).isEqualTo(claim);
                }
                process.call(command("continue"));
            }
            var evidence = process.call(command("terminal"));
            assertThat(evidence.path("workersStarted").intValue()).as("worker 상태 %s", evidence.path("status")).isEqualTo(1);
            assertThat(evidence.path("clicks").intValue()).as("worker 상태 %s", evidence.path("status")).isEqualTo(clicks);
            assertThat(evidence.path("uploads").size()).isEqualTo(uploads);
            assertThat(evidence.path("violations").size()).isZero();
            if (uploads > 0) {
                var manifest = starter.started().getFirst().snapshot().path("assetManifest");
                for (int i = 0; i < uploads; i++) {
                    assertThat(evidence.path("uploads").get(i).stringValue()).isEqualTo(manifest.get(i).path("sourceFingerprint").stringValue());
                }
            }
            // 권한 철회 뒤 CP는 마지막 상태만 읽는다. worker의 실패와 별개로 이미 기록한 SAVING을 보존한다.
            when(compiler.status(any(), any(), any())).thenAnswer(call -> process.call(command("status").set("stamp", loopbackJson.valueToTree(call.getArgument(2)))).path("status"));
            assertThat(jobQueries.refresh(user, conversationId, draftId, snapshotId).status()).isEqualTo(expected);
            if (revoke) {
                assertThatThrownBy(() -> saves.requestSave(user, origin.id(), snapshotId)).isInstanceOf(ContentDraftException.class);
            } else {
                assertThat(saves.requestSave(user, origin.id(), snapshotId).jobId()).isEqualTo(starter.started().getFirst().jobId());
            }
            assertThat(starter.started()).hasSize(1);
            assertThat(evidence.toString()).doesNotContain(starter.started().getFirst().ticket(), "비밀 원고");
        }
    }


    private String authorizeUrl(int httpPort) {
        String host = "host.docker.internal";
        return "http://" + host + ":" + httpPort + "/internal/content-draft-jobs/authorize";
    }


    private ObjectNode command(String operation) {
        return loopbackJson.createObjectNode().put("operation", operation);
    }


    /** 매번 새 운영 빈·HTTP server·DB 연결을 만들고, 기존 합성 H2 데이터만 유지한다. */
    private final class RestartCp implements AutoCloseable {
        private ConfigurableApplicationContext context;
        private int httpPort;


        RestartCp() {
            start();
        }


        int port() {
            return httpPort;
        }


        void restart() {
            context.close();
            start();
        }


        private void start() {
            context = new SpringApplicationBuilder(AssistantApplication.class)
                .profiles("test").initializers(application -> {
                    var registry = (GenericApplicationContext)application;
                    registry.registerBean("loopbackClock", Clock.class,
                        () -> Clock.fixed(loopbackInstant(), ZoneOffset.UTC), definition -> definition.setPrimary(true));
                    registry.registerBean("loopbackCatalog", HermesConnectorClient.class,
                        () -> catalog, definition -> definition.setPrimary(true));
                }).run("--server.port=" + httpPort, "--spring.jpa.hibernate.ddl-auto=validate");
            httpPort = ((WebServerApplicationContext)context).getWebServer().getPort();
        }


        @Override
        public void close() {
            if (context != null) {
                context.close();
            }
        }
    }

    private final class FixtureProcess implements AutoCloseable {
        private final Process process;
        private final BufferedReader reader;
        private final OutputStreamWriter writer;
        private final ExecutorService reads = Executors.newSingleThreadExecutor();


        FixtureProcess() throws Exception {
            var builder = new ProcessBuilder("bun", "--no-env-file", "tests/content-snapshot-loopback-runner.ts")
                .directory(Path.of("../hermes/connectors/naver-blog").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            String executablePath = builder.environment().get("PATH");
            builder.environment().clear();
            builder.environment().put("PATH", executablePath);
            builder.environment().put("HOME", System.getProperty("user.home"));
            process = builder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            try {
                assertThat(read(180).path("ready").booleanValue()).isTrue();
            } catch (Exception | AssertionError ex) {
                close();
                throw ex;
            }
        }


        JsonNode call(JsonNode command) throws Exception {
            writer.write(loopbackJson.writeValueAsString(command) + "\n");
            writer.flush();
            return read(35);
        }


        private JsonNode read(int seconds) throws Exception {
            return loopbackJson.readTree(reads.submit(reader::readLine).get(seconds, TimeUnit.SECONDS));
        }


        @Override
        public void close() throws Exception {
            try {
                writer.close();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                }
            } finally {
                reads.shutdownNow();
                reader.close();
            }
        }
    }
}
