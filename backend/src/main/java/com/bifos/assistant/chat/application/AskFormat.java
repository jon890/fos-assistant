package com.bifos.assistant.chat.application;

/**
 * 에이전트가 사용자에게 물을 때 답 끝에 둘 형식을 알린다.
 *
 * <p>web 이 이 형식을 선택 카드로 그리고, 고른 답을 다음 메시지로 보낸다. 형식을 그리는 쪽이 이 저장소라
 * 안내도 여기서 준다. 에이전트마다 스킬에 적으면 형식이 바뀔 때 저장소 두 곳을 함께 고쳐야 한다.
 * 형식을 바꾸면 {@code web/src/lib/ask.ts} 의 파서도 같이 바꾼다. 근거는 ADR-025 에 있다.
 *
 * <p>실행이 멈춰 답을 기다리지 않는다. 답은 평범한 다음 메시지로 온다. 그래서 기다리는 상태와 시간 제한이 없다.
 */
public final class AskFormat {

    public static final String GUIDE = """
            # 사용자에게 물을 때

            답을 이어 가려면 사용자가 정하거나 알려 줘야 하는 것이 있으면, 답 끝에 아래 형식을 한 번만 둔다.
            화면이 이것을 선택 카드로 그리고, 사용자가 고른 답이 다음 메시지로 온다.

            <ask>
            <question header="짧은 이름표">묻는 문장</question>
            <option description="고르면 어떻게 되는지">선택지</option>
            <option>선택지</option>
            </ask>

            - 태그 하나를 한 줄에 쓴다. 코드 블록 안에 두지 않는다
            - question 은 네 개까지 둔다. 선택지는 그 question 바로 아래에 여섯 개까지 둔다
            - header 는 40자, 묻는 문장은 400자, 선택지는 120자, description 은 240자 안에서 쓴다
            - 한 question 안에 같은 이름의 선택지를 두지 않는다
            - 속성 값에 큰따옴표가 필요하면 &quot; 로 쓴다
            - 여럿을 고를 수 있으면 question 에 multiple="true" 를 붙인다
            - 사용자는 언제나 직접 입력할 수 있다. 「기타」 선택지를 두지 않는다
            - 이름이나 날짜처럼 고를 수 없는 답이면 option 없이 question 만 둔다
            - 왜 묻는지는 <ask> 앞의 본문에 쓴다. 물을 것이 없으면 두지 않는다
            """.stripTrailing();

    private AskFormat() {
    }

    /** 사용자가 직접 답하는 대화의 instructions 에 안내를 붙인다. 흐름의 Chief 와 자식에게는 붙이지 않는다. */
    static String appendTo(String instructions) {
        return instructions == null || instructions.isBlank() ? GUIDE : instructions + "\n\n" + GUIDE;
    }
}
