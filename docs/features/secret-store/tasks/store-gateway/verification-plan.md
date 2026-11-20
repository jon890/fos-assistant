# 서버 비밀값 PR1a 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | `ADMIN` 과 `MEMBER` 사용자 | `ServerSecretAdminControllerTest` 의 권한 사례와 `admin-secrets.spec.ts` 의 메뉴·`MEMBER` 사례를 돌린다 | `MEMBER` 는 세 경로 모두 403 이고 줄이 바뀌지 않는다. 관리자 메뉴에 「서버 비밀값」 이 있고 `MEMBER` 는 화면에서 돌아간다 | H2 Control Plane 통합 테스트, 고정 응답 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ2 | 중계 서명 비밀을 만든 관리자 | 다시 만들기 뒤 `GET` 응답과 화면을 본다 | 「입력됨」, 앞부분 4자, 만든 관리자 이름과 시각이 보인다 | H2 통합 테스트, 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ3 | 저장된 중계 서명 비밀 | 저장소에서 원문을 읽어 `GET` 과 다시 만들기 응답 문자열에 그 원문이 있는지 본다 | 어느 응답에도 원문이 없다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ4 | KEK 가 있는 구성과 없는 구성 | `DatabaseServerSecretsTest` 의 암호문·이름 바꿔치기 사례와 컨트롤러의 KEK 없음 사례를 돌린다 | 줄의 값 칸이 원문이 아니고 다른 이름으로 옮기면 풀리지 않는다. KEK 가 없으면 409 `SECRET_STORE_UNAVAILABLE` 이고 줄이 없다. 화면에 KEK 문구가 보인다 | H2 통합 테스트, 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ5 | 중계 서명 비밀이 있는 상태 | `BrowserGatewayTokensTest` 에서 값을 바꾼 뒤 같은 컨텍스트로 표식을 만들고 옛 표식을 확인한다 | 재기동 없이 새 값으로 서명하고 옛 표식은 거절된다. 커밋 전 읽기가 옛 값을 캐시에 남기지 않는다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ8 | 로그 캡처 | 쓰기, 지우기, 다시 만들기를 하고 로그를 모은다 | 이름과 사용자 번호만 있고 원문과 앞부분이 없다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ9 | 옛 환경 변수에 값이 있는 구성 | `GatewaySecretImporterTest` 의 줄 없음, 줄 있음, 지운 줄 사례를 돌린다 | 처음에만 가져오고, 줄이 있거나 지운 줄이면 옛 값이 바뀌어도 가져오지 않는다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ18 | 브라우저 플러그인 바인딩 둘(`READY`, 재시작 대기)과 다른 바인딩 하나 | `ConnectorBrowserBindingReinstallTest` 와 `admin-secrets.spec.ts` 의 확인 창 사례를 돌린다 | 브라우저 플러그인 바인딩 둘만 다시 설치되고 재시작 대기로 남는다. 확인 창이 스펙 문구로 뜬다 | 가짜 대시보드 H2 통합 테스트, 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ18 | 운영 Control Plane, 브라우저 플러그인이 붙은 에이전트 | `remote-verification.md` 의 행을 따른다 | 다시 만들고 반영 완료한 뒤 그 플러그인 호출이 성공한다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
