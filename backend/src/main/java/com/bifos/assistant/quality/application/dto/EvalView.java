package com.bifos.assistant.quality.application.dto;

import com.bifos.assistant.quality.domain.EvalResult;
import com.bifos.assistant.quality.domain.QualityRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 평가 한 번이다. 관리자 API, 품질 화면과 코디네이터의 실행 스크립트가 읽는다. 질문과 답 본문은 담지 않는다.
 *
 * @param status {@code RUNNING}, {@code PASSED}(고른 묶음이 모두 통과), {@code FAILED}
 * @param tier 실제로 쓴 모델 단계. 기본 단계가 정해지지 않았으면 null
 * @param cleaned 모든 문항이 만든 것을 지웠는가
 * @param bundles 고른 묶음과, 끝난 뒤의 묶음 결과
 * @param items 문항 결과. 도는 동안에는 시작했거나 끝난 문항만 있다
 * @param environmentError 평가 비서의 Control Plane 연결이 끊겨 환경 오류(까닭 {@code ENVIRONMENT})로 끝난 문항이 있다. 그 실패는 품질이 아니라 평가
 * 환경의 문제다
 */
public record EvalView(
    UUID publicId,
    String trigger,
    String status,
    String tier,
    Instant startedAt,
    Instant finishedAt,
    Long elapsedMs,
    int itemCount,
    int modelCallCount,
    boolean cleaned,
    List<BundleView> bundles,
    List<ItemView> items,
    boolean environmentError) {


    /** 환경 오류로 끝난 문항의 까닭 코드다. 실행기의 {@code EvalRunner.ENVIRONMENT} 와 같다. */
    public static final String ENVIRONMENT = "ENVIRONMENT";

    public EvalView {
        bundles = List.copyOf(bundles);
        items = List.copyOf(items);
    }


    public static EvalView of(QualityRun run) {
        EvalResult result = run.evalResult();
        return new EvalView(
            run.publicId(),
            run.trigger().name(),
            run.status().name(),
            result.tier(),
            run.startedAt(),
            run.finishedAt(),
            run.elapsedMs(),
            run.itemCount(),
            run.modelCallCount(),
            result.cleaned(),
            result.bundles().stream().map(BundleView::of).toList(),
            result.items().stream().map(ItemView::of).toList(),
            result.items().stream().anyMatch(item -> ENVIRONMENT.equals(item.reasonCode())));
    }


    /**
     * 묶음 하나의 결과다.
     *
     * @param status 끝나기 전이면 null
     * @param errors 보내기·읽기 오류나 시간 초과로 끝난 문항 수
     */
    public record BundleView(String id, String status, int passed, int ran, int skipped, int errors) {

        static BundleView of(EvalResult.BundleResult bundle) {
            return new BundleView(bundle.id(), bundle.status(), bundle.passed(), bundle.ran(), bundle.skipped(), bundle.errors());
        }
    }

    /**
     * 문항 하나의 결과다.
     *
     * @param status {@code RUNNING}, {@code PASSED}, {@code FAILED}, {@code TIMED_OUT}, {@code SKIPPED}
     * @param failures 규칙 미달의 고정 코드와 대상 이름
     * @param startedAt 문항을 시작한 시각. 날짜 문항의 판정이 쓴다
     * @param conversationPublicId 남겨 둔 평가 대화. 통과해 지웠거나 보관 기간이 지나면 null
     */
    public record ItemView(
        String bundle,
        String question,
        String status,
        String reasonCode,
        List<String> failures,
        long elapsedMs,
        Instant startedAt,
        UUID conversationPublicId,
        List<Long> executionIds,
        boolean keep) {

        static ItemView of(EvalResult.ItemResult item) {
            return new ItemView(item.bundle(), item.question(), item.status().name(), item.reasonCode(), item.failures(), item.elapsedMs(),
                item.startedAt(), item.conversationPublicId(), item.executionIds(), item.keep());
        }
    }
}
