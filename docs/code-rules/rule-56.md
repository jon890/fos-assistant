# rule-56

색·간격·움직임은 테마 토큰을 쓰고 예외 inline에는 이유를 단다.

검사: [테마 토큰](../../test/unit/design-tokens.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

화면의 색과 간격, 움직임을 토큰으로 바꿔 일관되게 조정할 수 있게 한다.

## 예외와 검토 범위

색을 `#` 값이나 `bg-white`, `bg-black`으로 적지 않는다.
토큰이 없으면 `globals.css`에 더한다.
토큰으로 바꿀 수 없는 곳만 `artifact-panel.tsx`의 `bg-white`처럼 그 줄 위에 까닭을 주석으로 남긴다.
연속값처럼 클래스로 표현할 수 없는 inline과 토큰으로 바꿀 수 없는 색에는 바로 위에 까닭을 단다.
primary는 주 단추·선택·초점에만 쓴다.
안내는 Notice, 상태는 Badge의 의미 색을 쓴다.

## 이 저장소의 사례

[notice.tsx](../../web/src/components/ui/notice.tsx)에서 확인한다.
Notice는 안내와 오류 상자를 의미 색으로 그리는 공통 부품이다.

토큰은 globals.css의 @theme에 선언하고 Tailwind 클래스로 쓴다.
모서리의 과도한 둥글기와 임의 값, 인라인 스타일의 주석은 연결한 토큰 검사가 확인한다.
화면 코드의 하드코딩 색 금지와 그 예외, primary 사용 범위는 리뷰로 확인한다.
움직임의 길이와 곡선도 토큰을 쓴다.
