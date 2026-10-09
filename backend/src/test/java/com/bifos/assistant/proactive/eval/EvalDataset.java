package com.bifos.assistant.proactive.eval;

import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판단 평가 시나리오 묶음이다. 칸의 뜻은 {@code docs/features/proactive.md} 의 「fixture」 가 갖는다. 모든 값은 합성이다.
 *
 * @param note fixture 의 안내 글
 * @param providers 판단 기록을 가진 provider 의 흉내 비용과 지연
 * @param fallback 실패하는 provider 로 다시 돌릴 시나리오
 */
record EvalDataset(
        int version, String note, Map<String, ProviderProfile> providers, List<Scenario> scenarios, Fallback fallback) {

    static final String RESOURCE = "/proactive-eval/scenarios.json";

    static EvalDataset load() {
        try (InputStream in = EvalDataset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("fixture 가 없다: " + RESOURCE);
            }
            EvalDataset dataset = JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build()
                    .readValue(in, EvalDataset.class);
            dataset.requireUniqueProblemKeys();
            return dataset;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 결정적 provider 가 문제 키로 판단 기록을 찾으므로 시나리오 사이에 키가 겹치면 거절한다. 이력의 같은 키는 중복 시나리오의 뜻이다. */
    private void requireUniqueProblemKeys() {
        Set<String> seen = new HashSet<>();
        for (Scenario scenario : scenarios) {
            for (Problem problem : scenario.check().problems()) {
                if (!seen.add(problem.problemKey())) {
                    throw new IllegalStateException("문제 키가 시나리오 사이에 겹친다: " + problem.problemKey());
                }
            }
        }
    }

    Scenario scenario(String id) {
        return scenarios.stream()
                .filter(each -> each.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    /** 이 provider 가 그 문제 키에 남긴 판단 기록과 그 시나리오 안의 순서다. */
    Optional<Recorded> recorded(String providerId, String problemKey) {
        for (Scenario scenario : scenarios) {
            Map<String, Judgement> byKey = scenario.judgements() == null
                    ? Map.of()
                    : scenario.judgements().getOrDefault(providerId, new LinkedHashMap<>());
            int rank = 0;
            for (Map.Entry<String, Judgement> entry : byKey.entrySet()) {
                if (entry.getKey().equals(problemKey)) {
                    return Optional.of(new Recorded(entry.getValue(), rank));
                }
                rank++;
            }
        }
        return Optional.empty();
    }

    /** 흉내 비용과 지연이다. 실제 모델을 부르지 않으므로 기록한 값을 그대로 센다. */
    record ProviderProfile(
            String description, String model, long latencyMs, long inputTokens, long outputTokens, long costMicroUsd) {}

    /**
     * @param history 같은 점검 대화에서 먼저 돌린 살펴보기. 중복 판정의 바탕이다
     * @param historyGapHours 먼저 돌린 살펴보기마다 그 뒤로 흐르는 시간
     * @param decisionDelayHours 살펴보기가 끝난 뒤 가치 판단과 행동 정책을 부르기까지 흐르는 시간
     * @param agentWritesAllowed 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」
     * @param truth 문제 키마다 사람이 정한 기대
     * @param truthRank 사람이 정한 순서. 후보가 둘 이상인 시나리오만 쓴다
     * @param judgements provider 마다 문제 키별 판단 기록. 적힌 순서가 그 provider 의 추천 순서다
     * @param snapshot provider 마다 문제 키별로 지난번에 나온 행동 수준. 회귀 확인용이다
     */
    record Scenario(
            String id,
            String intent,
            List<Check> history,
            long historyGapHours,
            Check check,
            long decisionDelayHours,
            boolean agentWritesAllowed,
            Map<String, Truth> truth,
            List<String> truthRank,
            Map<String, LinkedHashMap<String, Judgement>> judgements,
            Map<String, Map<String, String>> snapshot) {

        List<Check> historyOrEmpty() {
            return history == null ? List.of() : history;
        }

        Map<String, String> snapshotOf(String providerId) {
            return snapshot == null ? Map.of() : snapshot.getOrDefault(providerId, Map.of());
        }
    }

    record Check(List<Finding> findings, List<Problem> problems) {}

    /** 확인 시각은 그 살펴보기의 시각이다. 살펴보기 결과 계약이 지금 확인한 발견만 받기 때문이다. */
    record Finding(String topicKey, String title) {}

    record Problem(
            String problemKey,
            String problem,
            String relatedGoal,
            List<String> evidence,
            String actionType,
            String actionText,
            String confidence,
            String expectedBenefit,
            String sideEffect,
            String risk,
            String changeSinceLast) {}

    /**
     * @param allowed 맞는 결과. 행동 수준 이름이거나 문제 찾기가 버렸다는 {@code DROPPED} 다
     * @param important 놓치면 안 되는 후보
     * @param lowValue 올리면 안 되는 낮은 가치 후보
     * @param duplicate 이미 다룬 같은 문제
     * @param requiresApproval 사람 승인 없이 실행하면 안 되는 후보
     */
    record Truth(
            List<String> allowed, boolean important, boolean lowValue, boolean duplicate, boolean requiresApproval) {

        static final String DROPPED = "DROPPED";

        boolean expectsSilence() {
            return allowed.stream().allMatch(each -> each.equals(DROPPED) || each.equals("IGNORE"));
        }
    }

    record Judgement(Map<DecisionAxis, DecisionLevel> axes, DecisionConfidence confidence) {}

    record Recorded(Judgement judgement, int rank) {}

    record Fallback(List<String> scenarios, List<String> providers) {}
}
