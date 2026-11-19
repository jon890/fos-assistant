# 파일 공간 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 임시 실행 공간에 xlsx 파일, 서명한 MCP 호출 | `McpFileSpacePutToolTest` 의 실행 공간 넣기 사례를 실행한다 | 요청자의 보관함에 파일이 있고 내려받은 바이트가 원본과 같다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ1 | 대화 폴더에 xlsx 와 HTML 파일 | 같은 테스트의 대화 폴더 상대 경로와 절대 경로 사례를 실행한다 | 두 경로 모두 들어가고, 다른 대화의 폴더 경로와 폴더 밖 절대 경로, 링크는 `NOT_FOUND` 다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ2 | 그룹 공개 에이전트와 주인이 아닌 요청자 | 같은 테스트의 그룹 공개 사례를 실행하고 다른 사용자로 보관본 주소를 연다 | 요청자의 목록에만 있고 다른 사용자는 없는 파일과 같은 응답을 받는다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ3 | 넣기 도구로 넣은 파일과 그 turn 의 답 | `FileSpaceTurnTest` 를 실행한다 | 메시지 응답에 `FILE` 항목이 있고 다시 읽어도 같다. 지우면 `deleted` 다. 넣기 도구 없이 대화 폴더에 쓴 xlsx 는 없다. 바뀐 HTML 이 없는 turn 에서도 묶인다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ3 | `FILE` 항목이 든 메시지 응답 대역 | `web/test/browser/artifact.spec.ts` 의 파일 줄 사례를 실행한다 | 답 아래에 제목과 종류, 내려받기, 「내 파일에서 보기」 가 보이고 지운 파일은 안내만 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ4 | 형식별 표본 바이트 | `VaultFileTypeTest` 와 `McpFileSpacePutToolTest` 의 형식 사례를 실행한다 | 받는 형식은 들어가고 zip 과 스크립트, 머리 바이트가 맞지 않는 파일은 `UNSUPPORTED_TYPE` 이며 보관함에 생기지 않는다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ5 | 25MiB 파일과 1바이트 큰 파일, 용량이 찬 사용자 | `ArtifactVaultFileTypeTest` 의 상한 사례를 실행한다 | 25MiB 는 들어가고 큰 파일은 거절된다. 용량이 차면 새 파일만 거절되고 있던 파일은 열린다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ5 | 마이그레이션과 25MiB 암호문 한 줄 | Draft CI 의 `backend-mysql` 결과를 본다 | 마이그레이션과 쿼리가 MySQL 에서 통과한다 | GitHub Actions | worker | push 뒤 Draft PR 의 CI | PR 뒤 |
| REQ6 | 같은 제목의 파일 둘, 제목 없는 파일, `artifact_write` 에 준 제목 | `ArtifactVaultFileTypeTest` 의 제목 사례와 `FileSpaceTurnTest` 의 `artifact_write` 제목 사례를 실행한다 | 둘째 제목에 「 2」 가 붙고, 제목 없는 파일은 제목이 비며, 목록 응답에 제목과 설명이 있고, DB 의 `vault_meta` 에 평문 제목이 없다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ7 | 넣기 도구가 있는 에이전트의 실행 입력 | 실행 입력 단락 테스트와 `McpFileSpacePutToolTest` 의 되풀이 사례를 실행한다 | 입력에 「파일 공간」 단락이 있고 같은 파일을 두 번 넣어 한 건이며 둘째가 `already` 다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ8 | 받지 않는 형식, 없는 파일, 용량 초과 | `McpFileSpacePutToolTest` 의 실패 사례와 `FileSpaceTurnTest` 를 실행한다 | 도구 결과가 `isError` 이고 넣지 못했다는 글과 까닭이 있으며 호스트 경로가 없다. 답 아래 `FILE` 항목과 보관함의 파일이 없다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ1, REQ7, REQ8 | 운영에 배포한 backend 와 web | 실제 에이전트에게 작업 폴더에 엑셀을 만들게 한 뒤 「내 파일 공간에 가져다 놔 줘」 라고 한다. 절차는 운영 저장소가 갖는다 | 답 아래 파일 줄이 보이고 「내 파일」 에서 내려받아 열린다. 에이전트가 파일 공간을 되묻지 않는다 | 운영 | 코디네이터 | 배포 뒤. 운영 DB 가 한 번에 받는 크기를 먼저 확인한다 | PR 뒤 |
