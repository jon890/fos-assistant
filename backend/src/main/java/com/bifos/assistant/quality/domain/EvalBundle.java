package com.bifos.assistant.quality.domain;

import com.bifos.assistant.quality.domain.type.CardAction;
import java.util.List;

/**
 * 평가 묶음 파일 하나다(docs/features/agent-quality/design.md 의 「묶음 파일」). 새 묶음은 파일을 더해 만들고 실행기를 고치지 않는다.
 *
 * @param id 묶음 id. 파일 이름과 같다
 * @param title 화면에 보일 이름
 * @param timeoutMinutes 문항 하나의 상한(분). 없으면 10
 * @param pass 묶음 통과 규칙. 없으면 통과 문항 비율 0.85
 */
public record EvalBundle(String id, String title, Integer timeoutMinutes, PassRule pass, List<EvalQuestion> questions) {


    public static final int DEFAULT_TIMEOUT_MINUTES = 10;
    public static final double DEFAULT_RATIO = 0.85;

    public EvalBundle {
        timeoutMinutes = timeoutMinutes == null ? DEFAULT_TIMEOUT_MINUTES : timeoutMinutes;
        pass = pass == null ? new PassRule(PassRule.RATIO, DEFAULT_RATIO) : pass;
        questions = questions == null ? List.of() : List.copyOf(questions);
    }


    /**
     * 묶음 통과 규칙이다.
     *
     * @param type {@code ratio} 는 통과 문항 비율. 답변 품질 기준({@code answer-quality})은 그 묶음을 옮기는 PR 이 더한다
     * @param ratio 돈 문항 가운데 통과해야 하는 비율. 올림한 수 이상이면 통과다
     */
    public record PassRule(String type, Double ratio) {

        public static final String RATIO = "ratio";


        public PassRule {
            ratio = ratio == null ? DEFAULT_RATIO : ratio;
        }
    }

    /**
     * 문항 하나다.
     *
     * @param tag 결과 보기에서 묶는 이름(옛 서비스 개념 평가의 개념). 판정에는 쓰지 않는다
     * @param keepConversation 통과해도 대화를 보관 기간만큼 남긴다
     */
    public record EvalQuestion(String id, String tag, List<EvalTurn> turns, EvalExpect expect, boolean keepConversation) {

        public EvalQuestion {
            turns = turns == null ? List.of() : List.copyOf(turns);
            expect = expect == null ? EvalExpect.NONE : expect;
        }
    }


    /**
     * 문항의 한 차례다.
     *
     * @param attachment 함께 올릴 리소스 사진 이름. 없으면 null
     * @param card 이 차례가 띄운 승인 카드를 어떻게 할지. 없으면 누르지 않는다
     */
    public record EvalTurn(String text, String attachment, CardAction card) {
    }

    /**
     * 규칙 판정의 기대다. 칸의 뜻은 docs/features/service-concepts/design.md 의 「평가 세트」 가 갖는다.
     *
     * @param cardStatus 마지막으로 누른 승인 줄이 이 상태여야 한다(예: {@code SUCCEEDED})
     */
    public record EvalExpect(
        List<String> links,
        List<String> linkPrefixes,
        List<String> noLinkPrefixes,
        List<String> tools,
        List<String> includes,
        List<String> absent,
        List<String> instructionIncludes,
        String cardStatus) {


        public static final EvalExpect NONE = new EvalExpect(null, null, null, null, null, null, null, null);

        public EvalExpect {
            links = copy(links);
            linkPrefixes = copy(linkPrefixes);
            noLinkPrefixes = copy(noLinkPrefixes);
            tools = copy(tools);
            includes = copy(includes);
            absent = copy(absent);
            instructionIncludes = copy(instructionIncludes);
        }


        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
