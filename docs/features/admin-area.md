# 관리자 영역

`ADMIN` 만 여는 `/admin` 아래 화면 묶음의 메뉴, 첫 화면(요약), 화면마다의 틀을 다루는 기능이다.

covers: `web/src/components/shell/admin-shell.tsx`, `web/src/app/admin/page.tsx`, `web/src/components/admin/admin-overview.tsx`, `backend/src/main/java/com/bifos/assistant/admin/usage/application/AdminOverviewService.java`

요구와 결정은 스펙 [`admin-area/spec.md`](admin-area/spec.md), 구조와 두 요약 경로의 응답은 설계 [`admin-area/design.md`](admin-area/design.md) 가 갖는다.
스펙의 PR1(메뉴 세 묶음, 휴대폰 서랍, 요약 화면)이 들어왔다. 관리자 화면들의 머리와 빈 상태 맞추기는 PR2 가 더한다.
지금의 틀과 메뉴는 [`web/docs/code-architecture.md`](../../web/docs/code-architecture.md) 의 「관리자 영역의 틀」, 화면 목록은 [`users.md`](users.md) 의 「관리자 영역」 이 갖는다.
