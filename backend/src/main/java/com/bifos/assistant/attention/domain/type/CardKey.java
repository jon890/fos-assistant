package com.bifos.assistant.attention.domain.type;

/**
 * 지금 화면의 카드 다섯이다.
 *
 * <p>선언 순서가 카드의 고정 순서다. 같은 항목이 두 카드의 후보가 되면 앞선 카드에 남는다. 사용자 제어가 이 이름을
 * 저장한다.
 */
public enum CardKey {
    FAILURES,
    NEEDS_ME,
    DELEGATED,
    CONTINUE,
    REPORTS
}
