# rule-42

긴 함수는 분리 후보로 표시한다.

검사: [MethodLength](../../backend/config/checkstyle/checkstyle.xml), [max-lines-per-function](../../web/eslint.config.mjs)

## 까닭

긴 함수에서 책임을 나눌 후보를 드러낸다.

## 예외와 검토 범위

Java와 화면 코드는 밀도가 달라 상한이 다르다.
숫자와 빈 줄 계산은 각 설정이 갖고 모두 warning으로만 보인다.

## 이 저장소의 사례

[eslint.config.mjs](../../web/eslint.config.mjs)에서 확인한다.
ESLint의 max-lines-per-function은 화면 함수의 분리 후보를 warning으로 알린다.
