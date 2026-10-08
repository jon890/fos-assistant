package com.bifos.assistant.mcp.application;

import java.text.Normalizer;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@code memory_remember} 의 바로 저장 판정이 보는 부정 표지다(ADR-20261008 / memory-remember-guard). 질문 원문과 본문 가운데 한쪽에만
 * 부정이 있으면 모델이 뜻을 뒤집었다고 보고 제안으로 내린다.
 *
 * <p>「안」 은 「안경」, 「안녕」, 「안방」 을 부정으로 보지 않도록 낱말로 떨어진 것과, 낱말 처음의 「안」 뒤에 정한 동사 첫 글자가 붙은 것만 본다.
 * 「못」 은 「잘못」 을 부정으로 보지 않도록 낱말 처음에 있거나 「지못」 으로 붙은 것만 본다.
 * 「아니」 는 활용형 「아냐」, 「아닌」, 「아님」, 「아닙」 도 함께 본다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NegationMarkers {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MARKER = Pattern.compile(
            "않|없|아니|아냐|아닌|아님|아닙|(?:^|\\s)못|지못|(?:^|\\s)안(?=$|\\s|먹|해|했|하|돼|되|좋|가|갔|와|왔|맞|마시|마셔|읽|봐|봤|보|싫)");

    /** 글에 부정 표지가 있는가(ADR-20261008 / memory-remember-guard). NFC 로 맞추고 연속 공백을 하나로 줄여 본다. */
    static boolean present(String text) {
        String normalized = WHITESPACE
                .matcher(Normalizer.normalize(text, Normalizer.Form.NFC))
                .replaceAll(" ");
        return MARKER.matcher(normalized).find();
    }
}
