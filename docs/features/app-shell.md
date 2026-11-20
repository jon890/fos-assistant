# 화면 틀과 메뉴 배치

대화에 집중하도록 사이드바, 이름 메뉴, 「설정」 화면, 입력창, 작업 과정 표시를 다시 배치하는 기능이다.

covers: `web/src/components/shell/sidebar.tsx`, `web/src/components/shell/main-nav.tsx`, `web/src/components/shell/app-shell.tsx`

아직 구현 전이다. 요구와 결정은 스펙 [`app-shell/spec.md`](app-shell/spec.md) 가 갖는다.
구현이 들어오면 이 파일에 화면 틀의 흐름을 적는다.

이 기능이 기대는 기존 규칙은 아래 문서가 갖는다.

- 지금의 화면 목록과 화면 틀, 관리자 영역의 틀: [`web/docs/code-architecture.md`](../../web/docs/code-architecture.md) 의 「화면 틀」
- 지금 화면과 알림: [`attention.md`](attention.md)
- 작업 과정 블록과 도구를 보이는 말: [`execution.md`](execution.md) 의 「작업 과정 블록」
- 새 대화 화면과 대화의 에이전트: [`chat.md`](chat.md) 의 「새 대화 화면」
- 관리자 영역에 두는 것과 일반 화면에 남긴 것: [`users.md`](users.md) 의 「관리자 영역」
