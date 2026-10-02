# Phase 06. ADR 의 대체 표시를 맞추고 INDEX 에 층 칸을 더한다

**Execution profile**: standard

## 목표

뒤 ADR 이 뒤집은 결정에 「대체된 부분」 표시를 달고, `docs/adr/INDEX.md` 에 층 칸을 더한다.
결정만 읽고 지나가는 독자가 낡은 결론을 얻는 것을 막기 위해서다.

**범위 외**

- ADR 본문을 줄이거나 다시 쓰지 않는다. 표시와 링크만 더한다.
- ADR 파일을 옮기거나 이름을 바꾸지 않는다.

## 컨텍스트

「대체된 부분」 을 적는 규칙이다.

- **결정 바로 아래**에 둔다. 문서 끝이나 대안 기각 안에 두지 않는다.
- 어느 결정이 어느 ADR 에 뒤집혔는지를 링크와 함께 한두 문장으로 적는다.
- 대체한 쪽 ADR 에서도 원본을 링크한다. 양쪽에서 찾을 수 있어야 한다.
- 전체가 대체된 ADR 은 남은 본문 앞에 「아래는 당시의 맥락이다」 는 뜻의 한 줄을 둔다. `docs/adr/ADR-006-*.md` 가 그 모양의 본보기다.
- 대체된 본문 문장은 지우지 않는다. 그때의 판단이 남아야 한다.

`INDEX.md` 는 지금 칸이 셋이다(번호, 제목, 상태). 일부만 대체된 ADR 은 상태 칸에 「Accepted. 모델 부분은 ADR-030 이 대체한다」 처럼 무엇을 어느 ADR 이 대체했는지 한 문장으로 적는다. ADR-005, 007, 032 의 줄이 그 본보기다. 이미 그렇게 적힌 줄은 바꾸지 않는다.

**근거 문서**: `docs/adr/INDEX.md`, `docs/adr/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md`(표시의 본보기)

## 의도 메모

- 층 값은 `공통`, `backend`, `frontend` 셋이다. 둘을 함께 적을 수 있다. 「Hermes 연동」 은 값으로 두지 않는다. backend 로 분류한 ADR 의 절반 이상이 Hermes 연동이라 값으로 두면 backend 칸과 거의 같아진다. Hermes 연동만 다루는 ADR 은 `공통` 이나 `backend` 가운데 그 결정을 지키는 코드가 있는 쪽으로 적는다.
- 상태 표기를 `accepted` 소문자로 모두 바꾸는 안은 버렸다. INDEX 와 코드 주석이 지금 표기를 쓴다. 머리 줄의 형식만 맞춘다.

## 작업 항목

### 1. 표시 없이 뒤집힌 결정에 「대체된 부분」 을 단다

발견마다 먼저 그 ADR 과 대체한 ADR 을 읽어 지금도 표시가 없는지 확인한다. 이미 있으면 건너뛴다.

| ADR | 뒤집힌 것 | 할 일 |
| --- | --- | --- |
| 018 | plugin 이 `register_token_route` 로 경로 둘만 연다는 서술. 실제 `hermes/plugins/dashboard-profile-api` 는 미들웨어를 감싸 요청을 더 많이 연다. ADR-030 이 대체했다는 표시는 문서 끝에만 있고 ADR-007 을 가리키지 않는다 | 결정 아래에 「대체된 부분」 을 두고 경로 서술이 당시 맥락임을 밝힌다. 지금의 목록은 `hermes/README.md` 를 가리킨다. 끝에 있는 ADR-030 표시를 결정 아래로 옮기고 ADR-007 링크를 더한다 |
| 002 | `hermes_profile_binding.credential_scope`. 그 표는 지워졌고 칸은 `agent` 에 있다. 「profile 은 사용자마다 하나」 는 ADR-007 이, 「금액을 계산할 수 없다」 는 ADR-004 가 뒤집었다 | 결정 아래에 「대체된 부분」 을 더한다. 표 이름은 `agent.credential_scope` 라고 그 안에 적는다 |
| 019 | soul 경로 등록을 비공개 저장소의 plugin 이 소유한다는 서술. ADR-041 이 plugin 원본을 이 저장소로 옮겼다 | 결정 아래에 「대체된 부분: plugin 소유는 ADR-041 이 바꿨다」 를 더한다 |
| 005, 042 | 042 가 005 의 Checkstyle 부분을 대체했는데 두 파일 모두 표시가 없다 | 005 에 「대체된 부분」 한 줄, 042 에 005 링크 한 줄 |
| 032 | 003, 017, 028 의 일부를 대체한다고 INDEX 에만 적혔다 | 032 의 적용 범위에 세 ADR 링크 한 줄. 세 ADR 쪽 표시가 032 를 링크하는지도 본다 |
| 038, 047 | 「관리자 원문 보기」 가 047 로 대체됐는데 038 의 표지가 「보완」 이고 결정 위에 있다 | 038 의 표지를 「대체된 부분」 으로 바꿔 결정 아래로 옮긴다. 047 에 038 링크가 있는지 본다 |
| 039, 029 | 043, 044, 045 가 더한 예외 표시가 없다 | 결정 아래에 링크 한 줄씩 |
| 041 | 019, 031, 037 을 바꿨다고 적었으나 링크가 없다 | 번호를 링크로 바꾼다 |
| 016 | 전체가 대체됐는데 「대체된 부분」 이름표와 당시 맥락이라는 문장이 없다 | ADR-006 과 같은 모양으로 맞춘다 |

### 2. 상태 머리 줄의 형식을 맞춘다

상태를 적는 모양이 셋이다. ADR-021 뒤의 파일이 쓰는 ``- **status**: `accepted` `` 줄로 맞춘다.

- ADR-001 부터 005: `- Status: Accepted` 줄을 ``- **status**: `accepted` `` 로 바꾼다. 자리는 그대로다.
- ADR-018 부터 020: `## 상태` 헤딩과 그 아래 값 한 줄을 지우고, h1 바로 아래에 ``- **status**: `accepted` `` 한 줄을 둔다. 그 절에 값 말고 다른 문장이 있으면 그 문장은 「대체된 부분」 으로 옮긴다.
- 값이 Superseded 인 파일은 `` `superseded` `` 로 적는다.
- ADR-006 부터 017 가운데 같은 두 모양을 쓰는 파일이 있으면 같이 맞춘다. `grep -L '\*\*status\*\*' docs/adr/ADR-*.md` 가 낸 파일이 대상이다.

### 3. `INDEX.md` 를 고친다

- **층 칸을 더한다.** 칸 순서는 번호, 제목, 층, 상태다. 값은 아래 표를 쓴다.
- **상태 칸에 일부 대체를 적는다.** 파일에 「대체된 부분」 이 있는 ADR 은 INDEX 상태 칸에도 위 본보기 꼴의 문장으로 적는다. 1번에서 표시를 단 것과, 이미 표시가 있는데 INDEX 에 없던 003, 017, 028, 031, 032, 033, 038 이 대상이다. 019 는 「plugin 소유는 ADR-041 이 대체한다」 다.
- **ADR-002 의 제목**을 파일의 제목과 같게 맞춘다. INDEX 를 파일에 맞춘다.
- **표에 없는 ADR.** `docs/adr/` 에 아래 표보다 큰 번호의 ADR 이 있으면 본문을 읽고 같은 기준으로 층을 정한다.

| 번호 | 층 | 번호 | 층 | 번호 | 층 |
| --- | --- | --- | --- | --- | --- |
| 001 | 공통 | 019 | backend | 037 | backend |
| 002 | backend | 020 | backend | 038 | backend, frontend |
| 003 | backend | 021 | backend | 039 | backend |
| 004 | backend | 022 | backend, frontend | 040 | backend |
| 005 | 공통 | 023 | frontend | 041 | 공통 |
| 006 | backend | 024 | backend, frontend | 042 | 공통 |
| 007 | backend | 025 | backend, frontend | 043 | backend |
| 008 | backend | 026 | backend, frontend | 044 | backend |
| 009 | frontend | 027 | backend, frontend | 045 | backend |
| 010 | backend | 028 | backend | 046 | backend |
| 011 | backend | 029 | backend | 047 | backend |
| 012 | backend | 030 | backend | 048 | backend, frontend |
| 013 | backend | 031 | backend | 049 | backend |
| 014 | backend | 032 | backend | 050 | backend |
| 015 | backend | 033 | backend | 051 | frontend |
| 016 | backend | 034 | backend | 052 | backend |
| 017 | 공통 | 035 | backend | 053 | backend |
| 018 | backend | 036 | backend | | |

INDEX 머리에 층 칸의 뜻을 한 줄로 적는다. 「층은 그 결정을 지키는 코드가 있는 쪽이다. 공통은 두 층과 `hermes/` 에 함께 걸린다」.

### 4. INDEX 의 ADR-050 상태 문구를 사실에 맞춘다

INDEX 나 ADR-050 머리에 「구현 전」 이라는 뜻의 문구가 남아 있으면 지운다. 승인 엔진은 구현돼 있다. `git grep -ln 'connector_action' backend/src/main` 이 마이그레이션과 엔티티를 낸다.

## 검증

```bash
# cwd: 저장소 root
# 1. INDEX 의 줄 수와 ADR 파일 수가 같다
test "$(grep -c '^| \[ADR-' docs/adr/INDEX.md)" = "$(ls docs/adr | grep -c '^ADR-')" && echo "INDEX 줄 수 일치"

# 2. INDEX 의 모든 줄에 층 값이 있다. 출력이 없어야 한다
grep '^| \[ADR-' docs/adr/INDEX.md | awk -F'|' '$4 !~ /공통|backend|frontend/ {print "층 없음: " $2}'

# 3. 상태 줄의 모양이 하나다. 출력이 없어야 한다
grep -L '\*\*status\*\*' docs/adr/ADR-*.md

# 4. 링크와 앵커. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs

# 5. 문서 경로 검사와 공개 정보 검사
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

기대값: 1번 「INDEX 줄 수 일치」 출력. 2번 출력 없음. 3번 출력 없음. 4번 출력에 `깨진 링크`, `없는 앵커` 가 든 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다. 5번 종료 코드 0.
`node --test` 는 `test/unit/doc-references.test.ts` 를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/adr/INDEX.md` | 수정 |
| `docs/adr/ADR-*.md` | 수정 |
