# rule-38

빈 문장·불완전한 equals/hashCode·문자열 ==·누락된 override·실수한 fallthrough를 막는다.

검사: [EmptyStatement](../../backend/config/checkstyle/checkstyle.xml), [EqualsHashCode](../../backend/config/checkstyle/checkstyle.xml), [StringLiteralEquality](../../backend/config/checkstyle/checkstyle.xml), [FallThrough](../../backend/config/checkstyle/checkstyle.xml), [MissingOverride](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

컴파일을 통과해도 결함으로 이어지는 표현을 일찍 발견한다.

## 예외와 검토 범위

fallthrough의 의도 표시는 Checkstyle의 판정에 따른다.
구체적인 token과 조건을 문서에 복제하지 않는다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
EqualsHashCode와 StringLiteralEquality가 컴파일로 드러나지 않는 실수를 검사한다.
