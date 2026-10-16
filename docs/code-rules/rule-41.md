# rule-41

문장·중괄호·메서드 구분은 정해진 형식을 따른다.

검사: [EmptyLineSeparator](../../backend/config/checkstyle/checkstyle.xml), [OneStatementPerLine](../../backend/config/checkstyle/checkstyle.xml), [LeftCurly](../../backend/config/checkstyle/checkstyle.xml), [RightCurly](../../backend/config/checkstyle/checkstyle.xml), [NeedBraces](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

포맷과 검사기가 서로 다른 모양을 요구하지 않게 한다.

## 예외와 검토 범위

한 줄 람다는 LeftCurly에서 제외한다.
빈 본문을 허용하는 닫는 중괄호 설정과 제어문의 same 설정은 formatter와 맞춘 것이다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
RightCurly의 두 설정은 제어문의 이어지는 본문과 독립된 메서드 본문을 구별한다.
