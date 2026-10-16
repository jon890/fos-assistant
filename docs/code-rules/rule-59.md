# rule-59

web import는 별칭을 쓰되 Node 테스트가 읽는 런타임 모듈은 상대 import를 쓴다.

검사: [no-restricted-imports](../../web/eslint.config.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

파일 이동에 안전한 import를 쓰되 Node 실행기가 별칭을 못 푸는 문제를 피한다.

## 예외와 검토 범위

NODE_TEST_READ_FILES와 그 런타임 의존은 상대 import를 쓴다.
실행 시 지워지는 import type은 별칭을 써도 된다.

## 이 저장소의 사례

[eslint.config.mjs](../../web/eslint.config.mjs)에서 확인한다.
NODE_TEST_READ_FILES가 Node 테스트가 런타임에 읽는 모듈을 별도로 지정한다.
