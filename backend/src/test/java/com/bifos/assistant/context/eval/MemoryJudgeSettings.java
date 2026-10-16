package com.bifos.assistant.context.eval;

import java.util.Map;
import java.util.Optional;

/**
 * 답 판정 하네스의 설정이다. 환경 변수로만 받고 기본은 꺼짐이다. 실제 판정 모델과 예산은 사용자가 정한다(ADR-20261015 / memory-eval-gate).
 *
 * @param baseUrl OpenAI 호환 API 의 주소
 * @param apiKey 그 API 의 key. 보고서와 로그에 내지 않는다
 * @param answerModel 답 모델
 * @param judgeModel 판정 모델
 * @param maxCalls 한 번 실행의 호출 상한. 월 예산을 이 값으로 준다
 * @param maxOutputTokens 호출 하나의 출력 상한
 */
record MemoryJudgeSettings(
    String baseUrl, String apiKey, String answerModel, String judgeModel, int maxCalls, int maxOutputTokens) {


    static final String BASE_URL = "MEMORY_JUDGE_BASE_URL";
    static final String API_KEY = "MEMORY_JUDGE_API_KEY";
    static final String ANSWER_MODEL = "MEMORY_JUDGE_ANSWER_MODEL";
    static final String JUDGE_MODEL = "MEMORY_JUDGE_JUDGE_MODEL";
    static final String MAX_CALLS = "MEMORY_JUDGE_MAX_CALLS";
    static final String MAX_OUTPUT_TOKENS = "MEMORY_JUDGE_MAX_OUTPUT_TOKENS";

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;

    /**
     * 환경 변수에서 읽는다. 주소가 없으면 하네스를 돌리지 않는다는 뜻으로 빈 값이다.
     *
     * @throws IllegalStateException 주소는 있는데 다른 필수 값이 없거나 숫자가 아닐 때. 메시지에 빠진 이름을 담는다
     */
    static Optional<MemoryJudgeSettings> fromEnv(Map<String, String> env) {
        String baseUrl = env.get(BASE_URL);
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        String maxOutput = env.get(MAX_OUTPUT_TOKENS);
        return Optional.of(new MemoryJudgeSettings(
            baseUrl,
            required(env, API_KEY),
            required(env, ANSWER_MODEL),
            required(env, JUDGE_MODEL),
            positive(MAX_CALLS, required(env, MAX_CALLS)),
            maxOutput == null || maxOutput.isBlank() ? DEFAULT_MAX_OUTPUT_TOKENS : positive(MAX_OUTPUT_TOKENS, maxOutput)));
    }


    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("답 판정 설정에 " + name + " 가 없다");
        }
        return value;
    }


    private static int positive(String name, String value) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 예외로 알린다
        }
        throw new IllegalStateException("답 판정 설정 " + name + " 는 양의 정수다");
    }


    /** key 를 내지 않는다. */
    @Override
    public String toString() {
        return "MemoryJudgeSettings[answerModel=" + answerModel + ", judgeModel=" + judgeModel + ", maxCalls=" + maxCalls
            + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
