package com.bifos.assistant.context.eval;

import com.bifos.assistant.context.eval.MemoryEvalScoreboard.Metrics;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.Ratio;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/**
 * 합성 측정의 악화 금지 게이트가 견주는 기준 값이다. 절차는 {@code docs/features/memory.md} 의 「기준 파일」 이 갖는다.
 *
 * <p>기준은 운영 기본값 모드의 전체 recall@5 와 오기억 노출률 둘이다. 사례 묶음의 버전과 묶여 있어, 사례가 바뀌면 다시 만든다.
 *
 * @param datasetVersion 이 값을 낸 사례 묶음의 버전
 * @param mode 이 값을 낸 측정 모드
 * @param recallAt5 전체 recall@5
 * @param falseMemoryExposure 전체 오기억 노출률
 */
record MemoryEvalBaseline(int datasetVersion, String mode, double recallAt5, double falseMemoryExposure) {


    static final String RESOURCE = "/memory-eval/baseline.json";

    /** 다시 만들 때 쓰는 원본 경로다. 테스트의 작업 디렉터리는 {@code backend/} 다. */
    static final Path SOURCE = Path.of("src", "test", "resources", "memory-eval", "baseline.json");

    /** 참이면 측정이 기준 파일을 다시 쓰고 게이트를 건너뛴다. Gradle 속성으로 넘긴다. */
    static final String WRITE_PROPERTY = "memoryEval.writeBaseline";

    static final String REWRITE_COMMAND = "./gradlew test --tests '*MemoryRecallEvalTest' -P" + WRITE_PROPERTY + "=true";

    /** 결과가 결정적이라 부동소수 비교의 오차만 둔다. */
    private static final double TOLERANCE = 1e-9;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 클래스패스의 기준 파일을 읽는다. 없으면 다시 만드는 명령을 담아 실패한다. */
    static MemoryEvalBaseline load() {
        try (InputStream in = MemoryEvalBaseline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("기준 파일이 없다: " + RESOURCE + ". " + REWRITE_COMMAND + " 로 만든다");
            }
            return JSON.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), MemoryEvalBaseline.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    /** 측정한 지표로 기준 값을 만든다. 분모가 0 인 비율은 0 으로 둔다. */
    static MemoryEvalBaseline of(int datasetVersion, String mode, Metrics metrics) {
        return new MemoryEvalBaseline(
            datasetVersion, mode, rateOf(metrics.recallAt5()), rateOf(metrics.falseMemoryExposure()));
    }


    /** 기준 파일을 쓴다. */
    void write(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    /**
     * 지금 값이 기준보다 나빠진 곳을 문장으로 낸다. 비면 게이트를 지난다.
     *
     * @param currentVersion 지금 사례 묶음의 버전
     * @param current 지금 측정한 같은 모드의 전체 지표
     */
    List<String> violations(int currentVersion, Metrics current) {
        if (currentVersion != datasetVersion) {
            return List.of("사례 묶음 버전 " + currentVersion + " 와 기준 파일 버전 " + datasetVersion + " 가 다르다. "
                + REWRITE_COMMAND + " 로 다시 만든다");
        }
        List<String> violations = new ArrayList<>();
        double recall = rateOf(current.recallAt5());
        if (recall < recallAt5 - TOLERANCE) {
            violations.add(String.format(Locale.ROOT, "recall@5 가 기준 %.4f 보다 낮다: %.4f", recallAt5, recall));
        }
        double exposure = rateOf(current.falseMemoryExposure());
        if (exposure > falseMemoryExposure + TOLERANCE) {
            violations.add(String.format(
                Locale.ROOT, "오기억 노출률이 기준 %.4f 보다 높다: %.4f", falseMemoryExposure, exposure));
        }
        return violations;
    }


    private static double rateOf(Ratio ratio) {
        return ratio.rate() == null ? 0 : ratio.rate();
    }
}
