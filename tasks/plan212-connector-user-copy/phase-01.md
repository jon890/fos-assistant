# Phase 01. 네이버 블로그 도구에 제목과 요약을 넣고 모든 커넥터에 필수로 한다

**Execution profile**: standard

## 목표

네이버 블로그 커넥터의 모든 도구에 화면용 `title` 과 `summary` 를 넣는다.
네이버 블로그 README 를 Gmail, 토스증권과 같은 모양의 사용자 안내로 다시 쓰고, 개발 내용은 개발 문서로 옮긴다.
그 뒤 커넥터 계약 검사가 모든 커넥터에서 두 칸을 필수로 본다.

**시작 조건**: PR #479(`jon890/naver-read-draft-safety`)와 #480(`jon890/content-snapshot-worker`)이 main 에 머지됐다.
두 PR 이 `hermes/connectors/naver-blog/connector.json` 과 README 를 고치고 있어서, 그 전에 시작하면 같은 줄이 충돌한다.
머지 여부는 `gh pr view 479 --json state` 와 `gh pr view 480 --json state` 로 본다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md` 의 「도구 이름과 요약」, `docs/adr/ADR-20261009-feature-docs.md` 의 커넥터별 기능 파일 예외, `hermes/connectors/README.md` 의 「문서와 소유자」 와 「공통 검사」

- 화면용 칸이 지나는 경로는 앞 PR 에서 끝났다. 대시보드 plugin 의 `tool_summaries`, Control Plane 의 `ConnectorToolSummary.summary`, 웹의 `ConnectorTool.summary` 다. 이 phase 는 경로를 바꾸지 않는다
- 계약 검사는 `hermes/tests/test_connectors_contract.py` 의 `check_connector` 다. 네이버 블로그는 `PENDING_USER_COPY` 로 빠져 있다
- Gmail 과 토스증권의 `connector.json`, README 가 본보기다
- 네이버 블로그의 쓰는 도구에는 이미 승인 카드용 `title` 이 있다. 같은 칸을 화면이 쓰므로 사용자가 읽는 말인지 다시 본다
- 카탈로그 계약 자료 `test/resources/connector-argument-catalog.json` 은 네이버 블로그를 담는다. 생성기로 갱신한다

## 작업 항목

### 1. `hermes/connectors/naver-blog/connector.json`

- 모든 도구에 `title` 과 `summary` 를 둔다. `description` 은 바꾸지 않는다. 모델 동작을 바꾸지 않기 위해서다
- 문체와 내용은 「도구 이름과 요약」 의 표를 따른다. 「~해요」체 한두 문장, 200자 안, 무엇을 하는지와 사용자가 알아야 할 한 가지(발행하지 않아요, 승인을 받아요 같은 것)만 적는다
- 도구 이름, 인자 이름, 반환 칸, ID 를 문구에 쓰지 않는다. `AGENTS.md` 「용어」 표를 따른다

### 2. 네이버 블로그 README 와 개발 문서

- README 는 「할 수 있는 일」, 「연결하는 방법」, 「도구 목록」, 「문제가 생겼을 때」 로 다시 쓴다. 도구 목록은 `| title | summary |` 줄을 그대로 둔다
- 인자, 오류 코드, 서버와 검사, 보안 같은 개발 내용은 새 기능 파일 `docs/features/naver-blog-connector.md` 로 옮긴다. `gmail-connector.md` 와 같은 모양이고 covers 는 `hermes/connectors/naver-blog/` 다. 내용을 버리지 않는다
- `connector.md` 의 「네이버 read_draft 사진 메타데이터」 처럼 네이버 블로그에만 해당하는 절도 그 파일로 옮긴다
- README 와 개발 문서를 가리키던 링크(ADR, `hermes/docs/code-architecture.md`, 스킬, 스크립트 주석)를 함께 고친다

### 3. 필수 검사 켜기

- `hermes/tests/test_connectors_contract.py` 에서 `PENDING_USER_COPY` 를 지우고 모든 커넥터에 검사를 건다
- `docs/features/connector.md` 「도구 이름과 요약」 의 `PENDING_USER_COPY` 문장을 지운다
- 웹의 「이름 없는 도구」 대체는 남긴다. 옛 카탈로그와 운영 목록 밖의 plugin 을 위한 것이다

### 4. 이 계획서를 지운다

구현 PR 에서 `tasks/plan212-connector-user-copy/` 를 지운다.

## 검증

```bash
# cwd: 저장소 root
bash scripts/checks/check-connectors.sh
python3 -m unittest discover -s hermes/tests
python3 hermes/tests/scripts/export_argument_catalog.py --check
scripts/dev/check-local.sh --fast
```

- 바뀐 한국어 문장은 `korean-check` 스킬의 검사기를 0 으로 통과한다
- 완료 보고에 네이버 블로그 도구별 `title` 과 `summary` 표를 넣는다. 사용자가 문구를 검토한다
