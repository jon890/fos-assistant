# 화면 틀과 메뉴 배치 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | `ADMIN` 세션, 대화 여럿 | `web/test/browser/shell.spec.ts` 의 사이드바 줄 테스트를 넓은 화면에서 돌린다 | 줄이 스펙 차례로만 보이고 「에이전트」, 「사용량」, 「고급」 이 없다. 「예약 작업」 은 `/tasks`, 「기억」 은 `/memory`. 「기억」 과 계정 메뉴 단추가 스크롤 없이 보인다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
| REQ2 | 같은 세션 | 같은 테스트를 360px 폭에서 돌려 서랍을 연다 | 서랍에 REQ1 과 같은 줄이 같은 차례로 보이고 화면 아래 탭 막대가 없다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
| REQ3 | `ADMIN` 과 `MEMBER` 역할 세션 | `web/test/browser/account-menu.spec.ts`. 역할 읽기 실패의 배치는 `account-menu.tsx` 를 읽어 확인한다(서버의 첫 읽기를 실패시킬 테스트 수단이 없다) | 이름 아래 설정, 관리자 화면, 화면 밝기, 로그아웃 차례이고 `MEMBER` 역할은 관리자 화면이 없다. 어둡게를 고르면 새로 고친 뒤에도 어둡다. 실패 상태에서 안내, 「다시 읽기」, 메뉴 단추가 형제이고 메뉴에 로그아웃이 있다. `Esc` 로 닫힌다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
| REQ4 | `ADMIN` 세션, 실행 하나 | `web/test/browser/settings.spec.ts` 와 `web/test/node/settings-area.test.ts` | 넓은 화면 `/settings` 에 묶음 넷과 에이전트 화면, 360px 에 묶음 목록만. `/connections` 를 바로 열면 그 줄이 골라진 설정 틀. 목록에 「예약 작업」 과 「기억」 이 없다 | 브라우저 테스트(가짜 Control Plane), 노드 테스트 | worker | 없음 | PR 전 |
| REQ5 | 대화 하나 | 대화를 연 뒤 이름 메뉴 → 「설정」 → 「대화로」 | 그 대화가 열린다. `/tasks` 와 `/memory` 에는 사이드바가 보인다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
| REQ6 | 대화 하나가 있는 세션 | `web/test/browser/sign-out.spec.ts` | 로그아웃하면 `/signin`. 뒤로 가기와 `/chat/{id}` 도 로그인 화면이다. `/signout` 이 500 이거나 끊기면 안내가 보이고 화면이 그대로다. 다른 browser context 의 로그인은 그대로다(JWT 쿠키라 브라우저마다 따로다). 같은 계정 세션을 다시 만들면 대화 목록이 그대로다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
| REQ6 | 운영에 배포된 웹, 사용자의 Google 계정 | 로그아웃한 뒤 「Google 계정으로 로그인」 을 누른다 | Google 계정 고르기 화면이 먼저 보인다 | 배포 뒤 실제 Google 로그인 | 코디네이터 | 배포 | PR 뒤 |
| REQ7 | `ADMIN` 세션, 읽지 않은 알림 하나 | `web/test/browser/shell.spec.ts` 의 알림 단추 테스트 | 사이드바 맨 위 앱 이름 옆에 알림 단추와 수가 있고 맨 아래 줄에는 없다. 360px 위 막대의 알림 단추는 그대로다 | 브라우저 테스트(가짜 Control Plane) | worker | 없음 | PR 전 |
