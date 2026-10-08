package com.bifos.assistant.memory.application;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 제목이나 본문이 민감해 보이는가. 바로 저장을 막는 데만 쓴다(ADR-20261008 / memory-remember-guard).
 *
 * <p>모델이 {@code sensitive} 를 거짓으로 줘도 건강, 금융, 신원과 신념의 낱말이나 여섯 자리 이상의 숫자열, 메일 주소가 있으면 제안으로 내린다.
 * 낱말 목록은 잘못 걸리는 경우가 있다. 잘못 걸려도 제안 카드가 될 뿐이라 넓게 잡는다. 민감도 값은 바꾸지 않는다.
 * 생일과 기념일 같은 날짜(「1990-03-05」, 「2015.10.08」)는 숫자열로 세지 않도록 숫자열을 찾기 전에 지운다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MemorySensitiveHints {
    private static final int MIN_DIGITS = 6;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}(?!\\d)");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d[\\d -]*\\d");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    /** 한 글자 낱말은 넣지 않는다(「약」 이 「약속」, 「예약」 에 걸린다). */
    private static final List<String> WORDS = List.of(
            // 건강
            "병원",
            "진단",
            "질환",
            "질병",
            "병력",
            "지병",
            "수술",
            "입원",
            "처방",
            "복용",
            "투약",
            "우울",
            "공황",
            "장애",
            "정신과",
            "임신",
            "당뇨",
            "혈압",
            "항암",
            "알레르기",
            "알러지",
            "치료",
            "증상",
            "투병",
            "건강검진",
            // 금융
            "계좌",
            "카드번호",
            "신용카드",
            "비밀번호",
            "암호",
            "대출",
            "빚",
            "부채",
            "연봉",
            "월급",
            "급여",
            "소득",
            "재산",
            "자산",
            "주식",
            "코인",
            "보험",
            "세금",
            "신용점수",
            "파산",
            "적금",
            "예금",
            // 신원과 신념
            "주민등록",
            "주민번호",
            "여권",
            "운전면허",
            "종교",
            "신앙",
            "기독교",
            "천주교",
            "불교",
            "이슬람",
            "교회",
            "성당",
            "정당",
            "투표",
            "성적지향",
            "동성애",
            "성정체성",
            "트랜스젠더",
            "국적",
            "체류",
            "비자",
            "전과",
            "범죄");

    /** 제목이나 본문 가운데 하나라도 민감해 보이면 참이다. 낱말은 공백을 뺀 글에서, 숫자열과 메일은 원래 글에서 찾는다. */
    public static boolean suspected(String title, String content) {
        return suspected(title) || suspected(content);
    }

    private static boolean suspected(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        String compact = WHITESPACE.matcher(normalized).replaceAll("");
        return WORDS.stream().anyMatch(compact::contains)
                || EMAIL.matcher(normalized).find()
                || hasLongDigitRun(normalized);
    }

    private static boolean hasLongDigitRun(String text) {
        Matcher matcher = DIGIT_RUN.matcher(DATE.matcher(text).replaceAll(" "));
        while (matcher.find()) {
            if (matcher.group().chars().filter(Character::isDigit).count() >= MIN_DIGITS) {
                return true;
            }
        }
        return false;
    }
}
