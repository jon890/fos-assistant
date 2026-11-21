package com.bifos.assistant.quality.domain;

import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 평가 한 번({@code kind} {@code EVAL})의 {@code quality_run.result_json} 이다(docs/features/agent-quality/design.md 의 「저장:
 * {@code quality_run} 의 {@code EVAL}」). 질문과 답 본문은 담지 않는다.
 *
 * @param tier 실제로 쓴 모델 단계. 고르지 않았으면 평가 사용자에게 걸리는 기본 단계를 풀어 적는다
 * @param bundles 고른 묶음과, 끝난 뒤의 묶음 결과
 * @param items 문항 결과. 문항을 시작할 때 대화 번호와 함께 먼저 더한다
 * @param cleaned 모든 문항이 만든 것을 지웠으면 참. 거짓인 끝난 줄은 다음 시작과 하루 정리가 다시 비운다
 */
public record EvalResult(String tier, List<BundleResult> bundles, List<ItemResult> items, boolean cleaned) {


    private static final JsonMapper JSON = JsonMapper.builder().build();

    public EvalResult {
        bundles = bundles == null ? List.of() : List.copyOf(bundles);
        items = items == null ? List.of() : List.copyOf(items);
    }


    /** 시작할 때의 빈 결과다. 묶음은 고른 id 만 적힌다. */
    public static EvalResult started(String tier, List<String> bundleIds) {
        return new EvalResult(tier, bundleIds.stream().map(BundleResult::pending).toList(), List.of(), false);
    }


    public EvalResult withItem(ItemResult item) {
        List<ItemResult> next = new ArrayList<>(items);
        next.removeIf(existing -> existing.sameQuestion(item));
        next.add(Objects.requireNonNull(item, "item"));
        return new EvalResult(tier, bundles, next, cleaned);
    }


    public EvalResult withItems(UnaryOperator<ItemResult> change) {
        return new EvalResult(tier, bundles, items.stream().map(change).toList(), cleaned);
    }


    public EvalResult withBundles(List<BundleResult> results) {
        return new EvalResult(tier, results, items, cleaned);
    }


    public EvalResult markCleaned() {
        return new EvalResult(tier, bundles, items, true);
    }


    static String write(EvalResult result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write eval result", ex);
        }
    }


    static EvalResult read(String json) {
        if (json == null || json.isBlank()) {
            return new EvalResult(null, List.of(), List.of(), false);
        }
        try {
            return JSON.readValue(json, EvalResult.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read eval result", ex);
        }
    }


    /**
     * 묶음 하나의 결과다.
     *
     * @param passed 통과 문항 수. 끝나기 전이면 0
     * @param ran 돈 문항 수(건너뜀 제외)
     * @param skipped 자리 값이 없어 건너뛴 문항 수
     * @param errors 보내기나 읽기, 시간 초과로 끝난 문항 수. 분모에 들고 통과로 세지 않는다
     * @param status 끝나기 전이면 null, 끝나면 {@code PASSED} 나 {@code FAILED}
     */
    public record BundleResult(String id, String status, int passed, int ran, int skipped, int errors) {

        static BundleResult pending(String id) {
            return new BundleResult(id, null, 0, 0, 0, 0);
        }
    }

    /**
     * 문항 하나의 결과다.
     *
     * @param conversationId 문항 대화의 내부 번호. 끊겨도 비울 대상을 찾게 시작할 때 적는다
     * @param conversationPublicId 남겨 둔 대화의 공개 식별자. 통과해 지웠거나 보관 기간이 지나면 null
     * @param executionIds 문항의 루트 실행 번호들. 대화를 지우면 빈 목록
     * @param keep 통과해도 대화를 보관 기간만큼 남기는 문항
     * @param failures 통과하지 못한 까닭의 고정 코드와 대상 이름. 답 본문은 담지 않는다
     */
    public record ItemResult(
        String bundle,
        String question,
        EvalItemStatus status,
        String reasonCode,
        List<String> failures,
        long elapsedMs,
        Instant startedAt,
        Long conversationId,
        UUID conversationPublicId,
        List<Long> executionIds,
        boolean keep) {

        public ItemResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            executionIds = executionIds == null ? List.of() : List.copyOf(executionIds);
        }


        boolean sameQuestion(ItemResult other) {
            return bundle.equals(other.bundle) && question.equals(other.question);
        }


        /** 대화를 지운 뒤 남길 모양이다. 내부 번호는 남긴다. 비우기가 끝나지 않았으면 그 번호로 대화가 만든 것을 다시 찾는다. */
        public ItemResult withoutLinks() {
            return new ItemResult(bundle, question, status, reasonCode, failures, elapsedMs, startedAt, conversationId, null, List.of(), keep);
        }
    }
}
