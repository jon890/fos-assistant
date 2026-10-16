# rule-57

외부 글은 신뢰하지 않는 Markdown으로 그린다.

검사: [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

에이전트와 외부 입력의 HTML이 사용자 화면에서 실행되지 않게 한다.

## 예외와 검토 범위

react-markdown의 HTML을 신뢰하지 않는 기본값을 바꾸지 않는다.
HTML 결과물의 격리된 미리보기와 Markdown 본문은 다른 경계다.

## 이 저장소의 사례

[markdown.tsx](../../web/src/components/chat/markdown.tsx)에서 확인한다.
chat의 markdown 부품은 에이전트 본문을 Markdown으로 렌더한다.
