# hermes ADR 목록

Hermes 에 설치하는 plugin 과 profile 틀, 커넥터(`hermes/`) 한 층의 코드가 지키는 결정이다.
여러 모듈에 걸친 결정과 둘 곳, 작성 규칙은 [`docs/adr/INDEX.md`](../../../docs/adr/INDEX.md) 가 갖는다.

## 결정 목록

| 식별자 | 제목 | 상태 |
| --- | --- | --- |
| [ADR-064](ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md) | 범용 커넥터는 이 저장소의 `hermes/connectors/` 에 두고 저장소가 유지보수한다 | Accepted. ADR-043 의 plugin 가운데 누구나 쓸 수 있는 것의 자리를 정한다 |
| [ADR-066](ADR-066-gmail-커넥터는-직접-만든-mcp-서버와-gmail-modify-scope-하나로-돌고-휴지통은-서버가-막는다.md) | Gmail 커넥터는 직접 만든 MCP 서버와 `gmail.modify` scope 하나로 돌고 휴지통은 서버가 막는다 | Accepted. 서버와 scope 는 ADR-084 로 부분 대체했다 |
| [ADR-084](ADR-084-gmail-typescript-filters.md) | Gmail 커넥터는 TypeScript 묶음 파일로 실행하고 필터 권한을 따로 받는다 | Accepted. ADR-066 의 서버와 단일 scope 를 대체한다 |
| [ADR-088](ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md) | 대시보드 plugin 은 감싸는 경로의 바꿔 끼우기를 두고, 기대는 Hermes 내부 지점을 계약 시험으로 확인한다 | Accepted. ADR-041 의 plugin 을 기능 모듈로 나누는 경계를 정한다 |
| [ADR-20261008 / connector-output-files](ADR-20261008-connector-output-files.md) | 커넥터는 계산할 목록을 그 에이전트 실행 공간에 읽기 전용으로 붙는 파일로 내고, 계산은 스크립트가 한다 | Accepted. ADR-086 의 실행 공간에 커넥터 출력 디렉터리를 더한다 |
| [ADR-20261008 / execute-code-unattended](ADR-20261008-execute-code-unattended.md) | docker 실행 공간을 쓰는 profile 은 API 경로의 `execute_code` 를 승인 없이 컨테이너에서 돌린다 | Accepted. ADR-086 의 실행 공간 설정에 `approvals.unattended_mode` 를 더한다 |
