package com.bifos.assistant.architecture;

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;
import java.util.regex.Pattern;

/**
 * 기준 파일의 위반 줄을 패키지 이름 없이 단순 클래스 이름으로 견준다.
 *
 * <p>{@code archunit.properties} 의 {@code freeze.lineMatcher} 가 이 클래스를 가리킨다.
 * 클래스를 다른 패키지로 옮겨도 위반 줄의 전체 이름만 달라지므로 기준을 다시 얼리지 않는다.
 * ArchUnit 기본 matcher 처럼 줄 번호({@code :12)})와 익명 클래스·람다의 번호({@code $1})도 무시한다.
 *
 * <p>{@code com.bifos.assistant.} 로 시작하는 이름 가운데 대문자로 시작하는 마디(클래스 이름) 앞의 패키지만 지운다.
 * 패키지 이름만 있는 줄은 그대로 둔다. 패키지를 지우면 서로 다른 기능의 같은 이름 패키지가 겹쳐 새 위반이 기존 줄에 가려진다.
 * 이 견줌은 운영 클래스의 단순 이름이 유일하다는 것에 기대고, {@code SIMPLE_CLASS_NAMES_ARE_UNIQUE} 가 그것을 지킨다.
 */
public final class ClassNameViolationLineMatcher implements ViolationLineMatcher {

    private static final Pattern PACKAGE_OF_CLASS = Pattern.compile("com\\.bifos\\.assistant\\.(?:[a-z][A-Za-z0-9_]*\\.)*(?=[A-Z])");
    private static final Pattern LINE_NUMBER = Pattern.compile(":\\d+\\)");
    private static final Pattern GENERATED_NUMBER = Pattern.compile("\\$\\d+");


    @Override
    public boolean matches(String first, String second) {
        return normalize(first).equals(normalize(second));
    }


    static String normalize(String line) {
        String withoutPackage = PACKAGE_OF_CLASS.matcher(line).replaceAll("");
        String withoutLine = LINE_NUMBER.matcher(withoutPackage).replaceAll(":0)");
        return GENERATED_NUMBER.matcher(withoutLine).replaceAll("\\$0");
    }
}
