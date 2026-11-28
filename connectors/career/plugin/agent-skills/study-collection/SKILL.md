---
name: study-collection
description: 읽을거리를 수집하고 공부 추천 리포트를 HTML 로 만든다. "읽을거리 수집", "소스 추가", "소스 끄기", "공부 추천 리포트", "추천 HTML 공유" 같은 요청에 사용한다.
---

# 공부 자료 수집과 리포트

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
소스, 수집 자료, 추천 이력은 이 저장소가 갖는다.
추천 저장은 아래 실행기 명령으로만 한다.

## 1. 맥락 문서와 점검

실행기는 `learning-interests` 문서를 작업 폴더의 `context/` 에서만 읽는다.
기억에서 제목이 「커리어: 학습 관심사」(`learning-interests`)인 문서를 `memory_search` 로 찾아 `memory_read` 로 읽고, 본문을 고치지 않고 `<작업 폴더>/context/learning-interests.md` 에 쓴다.
제목이 정확히 같은 기억만 맥락 문서다. 제목이 다르면 내용이 비슷해도 맥락 문서로 쓰지 않고, 다른 기억을 모아 본문을 지어내지 않는다.
문서가 없으면 실행기를 부르지 않는다. 그 제목의 문서가 없다고 알리고, `career-connector` 스킬의 「맥락 문서 읽고 고치기」 로 문서를 기억에 먼저 남긴 뒤 다시 하자고 안내하고 끝낸다.
같은 제목의 기억이 둘 이상이면 고르지 않고, 실행기를 부르지 않은 채 어느 제목이 겹쳤는지 알리고 끝낸다.
`context/` 의 파일은 실행기에 넘기는 사본이고 원본은 기억이다.

그다음 `<CAREER_LOCAL> study --doctor` 를 실행한다.
실행기가 `CAREER_STORE_BUSY` 나 `CAREER_STORE_CORRUPT` 로 끝나면 이어 가지 않고 표준 오류 첫 줄을 최종 답변 첫 줄에 적는다.

## 2. 수집과 리포트

수집, 후보 준비, 리포트, 추천 저장은 `references/execution.md` 를 읽고 따른다.
정리는 5번에서 한다.
자료를 고르는 기준은 `references/execution.md` 의 「고르는 기준」 을 따른다.
자료는 실행기가 만든 후보풀에서만 고른다. 실행기 명령이 실패하면 웹 검색이나 직접 찾은 글, 직접 만든 후보풀과 리포트로 대신하지 않는다.
표준 오류가 내가 쓴 `reading-selection.json` 의 형식을 가리키면 고쳐서 같은 명령을 다시 실행한다. 그 밖에는 다음 명령으로 넘어가지 않고, 실패한 명령과 표준 오류 첫 줄을 알리고 끝낸다.

## 3. 소스 관리

소스를 추가하거나 끄거나 바꿀 때는 `references/source-management.md` 를 읽는다.

## 4. 공유 링크

사용자가 요청했을 때만, 정리하기 전에 한다.
`<CAREER_LOCAL> study-validate --run-dir <RUN_DIR>` 와 직접 읽기로 HTML 에 개인 정보, 비공개 회사 맥락, 로컬 절대 경로가 없는지 확인한다.
통과하면 사용자가 가진 게시 수단으로 올린다.
게시한 URL 이 열리는지 확인한 뒤 기록한다.
기록 명령과 각 값의 형식은 `references/execution.md` 의 「실행」 절에서 `--record-publication` 명령을 읽는다.

`--record-publication` 을 실행할 때 `--run-dir` 이 없으면 실행기가 종료 코드 2 로 끝난다.
지난 추천과 기록한 게시 링크는 `<CAREER_LOCAL> study --list-recommendations` 로 본다.

## 5. 정리

공유를 마쳤거나 요청이 없으면 `<CAREER_LOCAL> study --cleanup --run-dir <RUN_DIR>` 를 실행한다.
