# rule-37

의도적으로 삼키는 예외는 ignored 또는 expected로 표시한다.

검사: [EmptyCatchBlock](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

예외를 삼키는 자리가 의도된 것인지 코드에서 보이게 한다.

## 예외와 검토 범위

ignored나 expected라는 이름은 표기 계약일 뿐이다.
삼켜도 되는 실패인지는 리뷰에서 확인한다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
EmptyCatchBlock 설정은 예외 변수 이름으로 의도적인 무시를 표시하게 한다.
