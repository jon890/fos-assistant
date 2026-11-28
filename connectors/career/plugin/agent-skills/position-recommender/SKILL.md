---
name: position-recommender
description: 공고 추천, 포지션 추천, 채용공고 분석 요청에 쓴다. 열린 공고를 실행기로 모아 후보자가 해온 일과 선호에 맞는지 판단하고 리포트를 만든다. "지원할 포지션 추천", "갈 만한 회사 찾아줘", "최신 백엔드 공고 분석", "이직 후보 추천" 같은 요청이 여기 해당한다.
---

# position-recommender

## 실행 환경

이 스킬은 fos-assistant 에이전트의 셸 실행 공간에서 돈다.
셸(`terminal`) 도구가 없으면 할 수 없다고 알리고 끝낸다.

실행기 명령은 `bun --no-env-file "${CLAUDE_PLUGIN_ROOT}/dist/career-local.js"` 이다.
`${CLAUDE_PLUGIN_ROOT}` 와 아래 환경 변수는 실행 공간이 넘긴 값을 셸이 푼다. 명령을 셸에 그대로 넘기고 경로를 짐작해 바꿔 쓰지 않는다.
`${CLAUDE_PLUGIN_ROOT}` 는 이름과 달리 이 실행 공간에 붙은 플러그인 폴더일 뿐이고, 실행 환경을 가리키지 않는다.
절차는 이 문서와 이 스킬의 `references/` 가 전부다. 플러그인 폴더의 다른 파일에서 절차를 찾지 않는다.
실행 공간의 파일은 `read_file`, `write_file`, `patch` 같은 파일 도구로 읽고 쓴다.
스킬 파일은 고치지 않는다. `skill_manage` 같은 스킬 편집 도구를 부르지 않고, 절차가 막히면 막힌 곳을 알린다.
이 문서와 `references/` 에서는 그 명령을 `<CAREER_LOCAL>` 로 적는다.
데이터는 커리어 작업 폴더의 `state/career.sqlite` 에 있다. 작업 폴더는 `CAREER_WORKSPACE_ROOT` 이고 없으면 `/workspace/career` 다.
커리어 Backend 주소와 token 은 쓰지 않는다.
공시 조회에 쓰는 `CAREER_DART_API_KEY`(또는 `CAREER_DART_API_KEY_FILE`)는 선택이다.
시작할 때 `<CAREER_LOCAL> workspace paths --json` 으로 `evidenceDir` 를 확인한다.

실행기는 의존성을 포함한 배포 번들이므로 실행 환경에서 `bun install` 이나 `bun run build` 를 하지 않는다.
예약 실행 중에는 저장소의 코드, 템플릿과 스킬을 수정하지 않는다.
번들이 없거나 공개 HTML 검증이 실패하면 게시를 중단하고 유지보수 작업으로 보고한다.
소스를 고치고 번들을 다시 만드는 일은 별도 개발 브랜치에서 한다.

## 순서

1. 맥락 문서를 기억에서 작업 폴더로 옮긴다(「시작 전: 맥락 문서」). 문서가 하나라도 없으면 여기서 끝낸다.
2. `position collect` 로 공고를 모은다(「실행」).
3. 회사 판정 큐가 있으면 판정하고 `commit-company-tiers` 로 반영한다(「실행」).
4. 공고 분석 큐를 분석하고 `commit-analyses` 로 반영한다(「판단 근거」, 「실행」).
5. `position finalize` 로 리포트를 만든다(「실행」).
6. 리포트 사본을 `file_space_put` 으로 「내 파일」 에 넣는다(「결과와 공개 경계」).
7. 최종 답을 쓴다(「최종 답변」).

공고는 `collect` 가 만든 큐에서만 얻는다. 웹 검색이나 웹 페이지 읽기 도구(`web_search`, `web_extract` 등)로 공고를 찾지 않고, 큐 밖의 공고를 추천하지 않는다.
회사 판정도 실행기가 모은 근거만 쓰고 웹에서 근거를 더 찾지 않는다.
실행기 명령(`collect`, `commit-company-tiers`, `commit-analyses`, `finalize`)이 실패하면 웹 검색이나 직접 만든 공고 목록, 큐, 리포트로 대신하지 않는다. 다음 명령으로 넘어가지 않고 [실패 처리](references/failures.md)의 「실행기 실패」 대로 알리고 끝낸다.
큐가 비어도 웹 검색으로 채우지 않는다. stdout 의 다음 명령대로 `finalize` 까지 가서 0건을 그대로 알린다.

## 목표

**지금 열린 공고 가운데 지원할 가치가 있는 것을 고르고, 그 회사가 어떤 곳인지 세 축으로 판정한다.**

- 공고의 역할이 후보자가 해온 일과 맞는가
- 그 회사에서 기술적으로 성장할 수 있는가
- 그 팀이 커지고 있는가
- 보상과 복지가 지금보다 나은가

회사의 세 축을 하나의 점수로 합치지 않는다.
공개 근거가 없는 축은 지어내지 않고 `unknown`, 즉 「근거 없음」으로 남긴다.
아직 분석하지 않은 공고도 억지로 순위를 매기지 않고 분석 대기로 남긴다.

## 판단 근거

회사 판정과 공고 점수는 [판정 기준](references/judgment.md)을 따른다.
처리할 수 없는 항목은 [실패 처리](references/failures.md)의 코드와 행동을 따른다.

공고를 분석할 때는 실행 디렉터리의 `candidate-context.json` 에 담긴 `position-preferences` 와 `application-state` 본문으로 현재 역할 기준과 이직 우선순위를 확인한다.
구체적인 프로젝트 근거는 `evidenceDir` 에서 읽기 전용으로 확인한다.
디렉터리가 없거나 비었으면 근거가 없다고 밝히고 추측하지 않는다.
닫힘 여부와 개인 제외 조건은 모델이 추측하지 않는다.

## 시작 전: 맥락 문서

실행기는 후보자 맥락 문서를 작업 폴더의 `context/` 에서만 읽는다. 그래서 `collect` 전에 아래를 먼저 한다.

1. 기억에서 제목이 「커리어: 역할 선호」(`position-preferences`)와 「커리어: 지원 상태」(`application-state`)인 문서를 `memory_search` 로 찾아 `memory_read` 로 읽는다.
   제목이 정확히 같은 기억만 맥락 문서다. 제목이 다르면 내용이 비슷해도 맥락 문서로 쓰지 않고, 다른 기억을 모아 본문을 지어내지 않는다.
2. 읽은 본문을 고치거나 줄이지 않고 `<작업 폴더>/context/position-preferences.md`, `<작업 폴더>/context/application-state.md` 에 쓴다.
3. 문서가 하나라도 없으면 실행기를 부르지 않는다. 어느 제목이 없는지 알리고, `career-connector` 스킬의 「맥락 문서 읽고 고치기」 로 그 제목의 문서를 기억에 먼저 남긴 뒤 다시 하자고 안내하고 끝낸다.
4. 같은 제목의 기억이 둘 이상이면 고르지 않는다. 실행기를 부르지 않고 어느 제목이 겹쳤는지 알린 뒤 끝낸다.

`context/` 의 파일은 실행기에 넘기는 사본이고 원본은 기억이다. 기억 화면에서 고친 내용은 다음 실행에서 이 단계가 다시 옮겨 쓴다.

`position-preferences` 본문이 바뀌면 공고 분석의 기준이 바뀌어 저장된 분석을 다시 계산한다. 공백과 줄바꿈만 다른 것은 같은 기준으로 본다.

## 실행

저장소가 잠겼거나 깨졌으면 다른 사본으로 전환하지 않고 중단한다([실패 처리](references/failures.md)의 「저장소와 설정」).
JSON을 다루려고 `bun -e`, `python -c`, heredoc 같은 즉석 스크립트를 쓰지 않고 CLI stdout과 큐 파일을 읽는다.
게시한 URL 이 열리는지 확인한다.

| 명령 | 하는 일 | 다음 판단 |
| --- | --- | --- |
| `collect` | 열린 공고를 모으고 다음 큐를 만든다 | 회사 판정 또는 공고 분석 |
| `commit-company-tiers` | 회사 판정을 반영하고 공고 분석 큐를 만든다 | 공고 분석 |
| `commit-analyses` | 공고 분석을 반영한다 | 남은 분석 또는 최종화 |
| `finalize` | 추천 JSON과 검증된 HTML을 만든다 | 결과 전달 |

```bash
<CAREER_LOCAL> position collect
<CAREER_LOCAL> position commit-company-tiers --run <RUN_DIR>
<CAREER_LOCAL> position commit-analyses --run <RUN_DIR>
<CAREER_LOCAL> position finalize --run <RUN_DIR>
```

`collect`를 `--run` 없이 실행하면 작업 폴더 `runs/` 아래에 실행 폴더를 만들고 stdout 첫 줄에 `<RUN_DIR>` 을 낸다.
이 값을 이후 명령에 그대로 넘긴다.
각 명령의 stdout이 다음에 읽을 큐, 모델이 결과를 쓸 경로와 다음 명령을 알린다.
도움말과 오류 출력에 나오는 `position_run.ts <하위 명령>` 은 `<CAREER_LOCAL> position <하위 명령>` 으로 읽는다.
CLI가 결과 파일의 최상위 칸과 빈 `results`, `failures` 배열을 먼저 만든다.
`results`와 `failures` 항목의 칸은 `<CAREER_LOCAL> position schema company-tiers` 와 `<CAREER_LOCAL> position schema analyses` 가 출력하는 JSON Schema를 따른다.
칸 사이 조건은 JSON Schema에 나오지 않는다. `fitScore`는 `scoreBreakdown` 합계이고, 회사 판정의 조건은 아래 문장을 따른다.
모델은 `results`와 `failures`에만 쓰며, 큐의 각 항목을 둘 중 하나에 한 번씩 넣는다.
반영 결과가 `partial`이면 stdout에 나온 남은 항목만 다시 판단해 같은 명령을 실행한다.
`collect`가 후보자 맥락 문서가 없다며 멈추면 「시작 전: 맥락 문서」 를 다시 하고 `collect`를 다시 실행한다.

회사 판정 큐가 있으면 `<RUN_DIR>/company-evidence.json`에 저장된 근거만 읽고 판정한다.
`collect`가 실패한 수집기를 알려도 멈추지 않는다. 그 출처의 근거만 빠진 것이므로 저장된 근거로 판정을 이어 간다.
큐의 회사마다 `<RUN_DIR>/company-tier-updates.json`에 결과를 한 건씩 쓴다.
각 결과의 `signals`에는 세 축을 한 번씩 넣고, 등급을 매긴 축에는 해당 근거의 `id`를 `evidenceIds`로 연결한다.
`evidence`에는 실제 사용한 근거의 `id`, URL, 제목과 확인 날짜를 원본 그대로 옮긴다.
근거가 없는 축은 `level: "unknown"`, `evidenceIds: []`로 남긴다.
판단을 유보한 이유와 반대 근거는 `assessment`에 적고, 공개 `reason`은 확인된 사실만 200자 안에 쓴다.

## 결과와 공개 경계

HTML은 추천, 분석한 활성 공고 순위, 회사별 세 축, 분석 대기와 수집 경고를 구분한다.
최종화한 JSON은 비공개 분석 원본이다. 게시에는 최종화한 HTML 파일만 넘긴다.
HTML은 공고 이름과 링크, 공개 회사 근거와 축별 판정, 처리 건수와 수집 상태만 출력한다.
개인별 추천 이유, 상세 해석, 가정, 순위 메모, 지원 준비와 현재 직장 비교 기준은 JSON에만 남긴다.
회사 판정의 공개 근거는 HTTPS URL과 확인 시각을 가져야 한다.
사용자가 공유 링크를 요청했을 때만 사용자가 가진 게시 수단으로 게시한다.

공개 HTML과 최종 답변에는 다음 내용을 넣지 않는다.

- 후보자 기준과 개인 판단 근거
- 비공개 회사 제외 사유와 현재 보상
- 원본 오류 문구와 모델 응답 전문
- 회사 판정의 `assessment`
- 내부 경로, 환경 식별자와 token

`finalize` 는 HTML 사본을 작업 폴더 `reports/<날짜>-position.html` 에 남기고 그 경로를 `리포트 사본:` 줄로 알린다.
`리포트 사본:` 줄의 경로가 `/workspace/` 로 시작하면 그대로 `file_space_put` 의 `path` 로 넘겨 「내 파일」 에 넣는다. 넣기는 건너뛰지 않는다.
도구 이름은 접두사가 붙어 보일 수 있으니 이름 끝이 `file_space_put` 인 도구를 찾는다. `title` 은 「공고 추천 리포트 <날짜>」, `description` 은 한 줄 설명으로 쓴다.
`finalize` 가 `다음 단계:` 줄로 넣을 파일을 다시 알린다. 그 줄을 처리하기 전에는 정리와 최종 답변으로 가지 않는다.
도구가 실제로 없거나 실패하면 도구 결과의 까닭을 최종 답변의 「내 파일」 줄에 적는다.
브라우저 도구가 있으면 데스크톱과 모바일 배치, 가로 넘침과 주요 링크를 확인한다.
없으면 `finalize` 의 HTML 검증 결과만 전하고, 화면 배치는 확인하지 못했다고 최종 답변에 적는다.
검증과 전달이 끝나면 `<CAREER_LOCAL> position cleanup --run <RUN_DIR>` 로 정리한다.

## 최종 답변

실행할 때마다 같은 형식을 적용한다.
로컬 HTML 또는 검증된 공개 링크와 함께 다음 내용을 전달한다.

- 이번 실행 분석, 재사용, 분석 대기와 분석하지 못한 공고 건수
- 개인 제외 건수
- 회사 tier 출처별 건수와 회사 tier 평가 실패 건수
- 축별 「근거 없음」 회사 수(`unknownCompanyCounts`)
- 회사별 성장 범위, 팀 성장, 보상과 복지 판정과 각 근거
- 최종화 명령이 출력한 `collectionWarnings`의 각 줄
- 바로 검토할 공고의 이름과 링크
- 「내 파일」 줄: 넣은 파일 이름, 또는 넣지 못한 이유. 이 줄이 없으면 최종 답변이 아니다

`collectionWarnings`는 문구를 새로 쓰지 않고 그대로 옮긴다.
배열이 비어 있으면 수집 경고 줄을 만들지 않는다.
