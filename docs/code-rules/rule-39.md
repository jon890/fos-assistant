# rule-39

같은 표현은 단순한 boolean·표준 modifier·배열·long 표기를 쓴다.

검사: [SimplifyBooleanExpression](../../backend/config/checkstyle/checkstyle.xml), [SimplifyBooleanReturn](../../backend/config/checkstyle/checkstyle.xml), [ModifierOrder](../../backend/config/checkstyle/checkstyle.xml), [UpperEll](../../backend/config/checkstyle/checkstyle.xml), [ArrayTypeStyle](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

뜻이 같은 표현의 불필요한 차이를 줄인다.

## 예외와 검토 범위

자동으로 단순화할 수 있어도 평가 순서와 부작용이 바뀌지 않는지 확인한다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
SimplifyBooleanReturn과 ModifierOrder가 같은 의미의 표현을 정리한다.
