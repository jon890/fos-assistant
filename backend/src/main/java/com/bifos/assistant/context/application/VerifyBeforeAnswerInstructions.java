package com.bifos.assistant.context.application;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 대화 실행에 넣는 「확인을 마치고 답하기」 절이다. 요구와 결정은 {@code docs/features/answer-quality/spec.md} 의 DECISION31 이 갖는다.
 *
 * <p>근거가 모자란 채로 「확인하지 못했다」 만 되풀이하지 않고, 도구로 확인할 수 있는 것은 확인한 뒤 답하게 한다. 「최신 정보와 출처」 절
 * 바로 뒤에 둔다. 먼저 살펴보기와 예약 작업에는 넣지 않는다(같은 스펙의 DECISION14).
 *
 * <p>절 본문에 빈 줄을 두지 않는다. E2E 의 가짜 Hermes 가 지침 제목 뒤 한 문단씩 벗겨 낸다({@code test/e2e/fake-hermes/scenario-fixtures.ts}).
 * 글은 실행마다 같아야 한다. 바뀌는 값을 넣으면 프롬프트 캐시와 {@code instructions_hash} 가 깨진다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class VerifyBeforeAnswerInstructions {

    static final String SECTION = """
            # 확인을 마치고 답하기

            답하기 전에 이 질문에 답하려면 무엇이 확인돼야 하는지 먼저 정한다.
            근거가 없거나 하나뿐이면 web_search 도구가 있을 때 질의를 바꿔 가며 폭넓게 검색해 여러 근거를 서로 맞춰 본다. 공식 자료, 당사자와 관계없는 리뷰나 기사, 반대 근거(가품 의혹, 불만, 리콜 같은 것)를 함께 찾는다.
            도구로 확인할 수 있는 것은 확인하지 못했다고 적기 전에 먼저 확인하러 간다. 재고, 판매자와 수입자, 환불과 배송 조건, 가격처럼 검색하거나 페이지를 읽어 알 수 있는 것이 여기 든다.
            그래도 확인하지 못한 것은 답 하나에 한 번만 모아 적고, 다음 답에서 같은 말을 되풀이하지 않는다.
            사용자만 줄 수 있는 사실이 답을 바꾸면 그중 꼭 필요한 하나만 콕 집어 묻는다. 여러 개를 한꺼번에 묻지 않는다.
            다른 에이전트나 하위 작업에 맡긴 결과가 오면 앞 답을 되풀이하지 않고 새로 안 것을 합친 결론 하나로 답한다.
            """.stripTrailing();
}
