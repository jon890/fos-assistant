# Phase 04. 선언 검사 강화와 문서

**Execution profile**: standard

## 목표

플러그인 선언 검사가 도구 이름 모양, 설명 첫 문장의 영어 요지, 플러그인 사이 이름 중복을 잡고, 기능 파일과 ADR 이 이 PR 의 동작을 설명한다. 구현을 마친 이 계획 폴더를 지운다.

**범위 외**: PR2 부터 PR4 의 문서.

## 컨텍스트

**요구사항**: REQ5

**근거 문서**: `docs/features/naver-shopping/design.md` 의 「도구 이름과 설명」, DECISION6, DECISION8

- 검사 틀은 `hermes/tests/test_connectors_contract.py` 의 `check_connector`, `ContractCatchesViolationsTest.broken_copy`
- 문서 배치 규칙은 `test/unit/doc-files.test.ts`, ADR 규칙은 `docs/adr/INDEX.md`

## 작업 항목

### 1. 검사

`check_connector` 에 이름 모양과 영어 요지(예외 목록 gmail, naver-blog, tossinvest 와 늘리지 않는다는 주석). 디렉터리 목록을 받는 `cross_connector_problems` 와 그 테스트. 틀린 사본 테스트 셋.

### 2. 문서

`docs/features/naver-shopping-connector.md`(covers, 등록 칸, 도구와 정책, 주소 분류, 오류, 보안, 설정 안내, 실제 계정으로 확인하기), `docs/adr/ADR-20261110-naver-shopping-connector.md` 와 `docs/adr/INDEX.md`, `docs/features/connector-policy.md` 의 `attended_only` 와 `ATTENDED_ONLY`, `hermes/connectors/README.md` 의 선언 칸, `hermes/plugins/dashboard-profile-api/README.md` 의 정책 키, `backend/docs/data-schema.md` 의 `agent_execution.unattended`.

### 3. 계획 폴더 지우기

독립 verify 가 이 계획과 인수 검증 계획을 읽은 뒤, Ready 전 마지막 커밋에서 `docs/features/naver-shopping/tasks/shopping-read/` 를 지운다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
node --test test/unit/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/tests/test_connectors_contract.py` | 수정 |
| `docs/features/naver-shopping-connector.md` | 신규 |
| `docs/adr/ADR-20261110-naver-shopping-connector.md` | 신규 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `hermes/connectors/README.md` | 수정 |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/naver-shopping/tasks/shopping-read/` | 삭제 |
