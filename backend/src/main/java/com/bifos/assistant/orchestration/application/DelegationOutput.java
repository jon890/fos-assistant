package com.bifos.assistant.orchestration.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 위임 실행의 답을 실행 줄에 적을 길이로 맞춘다.
 *
 * <p>위임을 끝까지 돌린 자리와, 기동할 때 남은 위임 실행을 Hermes 의 답으로 적는 자리가 같은 규칙으로 자르게
 * 하려고 한곳에 둔다.
 */
@Component
@RequiredArgsConstructor
public class DelegationOutput {

    /** 위임 답을 잘랐을 때 끝에 붙이는 한 줄이다. 읽는 쪽은 모델이다. */
    static final String TRUNCATED_NOTICE = "[답이 %d자를 넘어 뒷부분을 잘랐다]";

    private final DelegationProperties delegation;

    /**
     * 위임 답을 상한까지 자르고 잘렸다는 한 줄을 붙인다. 답이 null 이면 빈 글이다.
     *
     * <p>상한 자리에서 대리 쌍이 갈리면 그 앞에서 자른다. 반쪽 글자가 남으면 저장과 JSON 쓰기에서 깨진다.
     */
    public String clip(String output) {
        if (output == null) {
            return "";
        }
        int max = delegation.outputMaxChars();
        if (output.length() <= max) {
            return output;
        }
        int end = max > 0 && Character.isHighSurrogate(output.charAt(max - 1)) ? max - 1 : max;
        return output.substring(0, end) + "\n\n" + String.format(TRUNCATED_NOTICE, max);
    }

    /** 멈춘 위임 실행이 그때까지 받은 답이다. 받은 답이 없으면 null 이라 실행 줄의 답을 비워 둔다. */
    public String partial(String output) {
        return output == null || output.isBlank() ? null : clip(output);
    }
}
