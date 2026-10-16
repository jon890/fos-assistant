# rule-55

브라우저의 API 호출은 서버 라우트와 lib 도우미를 거친다.

검사: [no-restricted-globals](../../web/eslint.config.mjs), [no-restricted-syntax](../../web/eslint.config.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

브라우저에 Control Plane 토큰을 주지 않고 호출과 오류 처리를 한 곳에 모은다.

## 예외와 검토 범위

app/api 서버 라우트가 세션에서 사용자를 정한다.
화면은 lib 도우미를 거친다.
lint는 직접 fetch와 일부 응답·본문 처리를 검사하지만 토큰 차단 전체를 증명하지는 않는다.

## 이 저장소의 사례

[eslint.config.mjs](../../web/eslint.config.mjs)에서 확인한다.
ESLint는 화면의 직접 fetch와 서버 라우트의 직접 본문 파싱을 별도로 검사한다.
