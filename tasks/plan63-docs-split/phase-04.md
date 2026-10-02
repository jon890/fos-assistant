# Phase 04. 코드와 어긋난 서술을 고친다

**Execution profile**: deep

## 목표

문서가 코드와 다르게 적은 사실을 코드에 맞춘다.
읽는 사람이 문서를 믿고 틀린 코드를 쓰는 것을 막기 위해서다.

**범위 외**

- 두 문서가 함께 적던 중복을 한 곳에 남기는 일과 헤딩 이름은 phase 05 다.
- ADR 의 「대체된 부분」 표시와 INDEX 는 phase 06 이다. 이 phase 는 ADR 본문의 사실 오류만 고친다.
- 코드를 고치지 않는다. 문서가 맞고 코드가 틀린 것으로 보이면 고치지 않고 결과 보고에 적는다.

## 컨텍스트

아래 표는 2026-10-02 의 문서 감사에서 나온 발견이다. 그 뒤 문서를 주제별 파일로 옮겼으므로 「지금 있는 곳」 은 옮긴 뒤의 파일이다.
줄 번호는 적지 않았다. 「찾을 글」 로 `git grep` 해서 자리를 찾는다.

**발견마다 먼저 다시 확인한다.** 감사 뒤에 main 에 머지된 변경이 있다.

1. 「찾을 글」 이 문서에 아직 있는가. 없으면 이미 고쳐진 것이다. 건너뛰고 결과 보고에 적는다.
2. 「증명」 의 코드를 열어 지금도 그러한가. 코드가 바뀌어 문서가 맞게 됐으면 건너뛰고 결과 보고에 적는다.
3. 둘 다 그대로면 「고칠 내용」 대로 고친다. 고칠 문장은 코드에서 읽은 사실로 쓴다. 표의 문장을 그대로 베끼지 않는다.

문서의 문체는 평서체(`~다`)다. 고친 문장도 같게 쓴다.
`AGENTS.md` 의 용어 표를 따른다. 사람은 「사용자」, 모인 단위는 「그룹」 이다.

**근거 문서**: `docs/model-tiers.md` 의 「모델 선택」 절, `docs/prd.md`, `AGENTS.md` 의 「용어」 절과 「공개 저장소」 절

## 의도 메모

- 문서에서 지우는 것이 답인 발견이 있다. 코드 상수와 1:1 인 표, 이미 사실이 아닌 「지금은」 문장이 그렇다. 고쳐 쓰는 것보다 지우는 것을 먼저 본다.
- 운영 값을 적지 않는다. 고친 문장에 홈서버의 주소, 포트, 경로, 컨테이너 이름, profile 이름을 넣지 않는다.
- 던지는 곳이 없는 오류 코드나 읽기만 남은 사건 종류를 코드에서 지우는 일은 이 phase 가 하지 않는다. 문서에서만 사실대로 적는다.

## 작업 항목

### 1. 틀린 코드를 쓰게 만드는 사실 오류 (Critical)

| 지금 있는 곳 | 찾을 글 | 현재 사실과 증명 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/backend/people.md` | `지금 읽기만 한다` | `HermesProfileKeyStore` 에 `write` 와 `delete` 가 이미 있다 | 「읽기, 쓰기, 지우기를 모두 갖는다」 로 바꾼다 |
| `docs/frontend/chat.md` 「모델을 고를 때」 | `profile 기본값으로 돈다` 와 그 절의 그림 | 선택 모드는 `DEFAULT`, `TIER`, `CUSTOM` 셋이다. 선택이 없으면 내 기본 단계, 그룹 기본 단계, profile 기본값 순서다. 증명은 `ModelTierService` 의 해석 메서드와 `docs/model-tiers.md` 의 「모델 선택」 | 순서 설명은 `docs/model-tiers.md` 를 가리키게 바꾼다. 그림의 마지막 분기를 「해석한 단계에 mapping 이 있다 / 없다」 로 고친다 |
| `docs/backend/people.md` 「사람을 더할 때」 의 그림 | `POST /api/admin/people` | Control Plane 의 경로는 `/api/v1/admin/people` 이다(`PeopleAdminController`). 적힌 경로는 Next.js 서버 라우트다 | 관리자 브라우저에서 Control Plane 으로 가는 화살표의 경로를 `/api/v1/admin/people` 로 고친다 |
| `docs/hermes/runs-api.md` | `세션 조회, 실행의 runtime, 대화가 고른 값` | `ExecutionRecorder` 는 runtime 에 provider 와 모델이 둘 다 있으면 그 짝을 먼저 쓴다. 문서의 순서는 runtime 이 불완전할 때만 칸마다 적용된다 | 「runtime 에 둘 다 있으면 그 짝을 쓴다. 없으면 세션 조회, runtime, 대화가 고른 값 순서로 칸마다 채운다」 는 뜻으로 바꾼다 |
| `docs/hermes/profiles.md` | `자동 활성화를 막는 설정을 넣지 않으므로` | `hermes/profile-template/config.yaml.template` 이 `platforms.api_server.enabled: false` 를 넣는다. 같은 파일의 뒤쪽 절과도 어긋난다 | 「설정 틀이 이 값을 넣으므로 Control Plane 이 만든 profile 은 경고를 남기지 않는다」 는 뜻으로 바꾼다 |

ADR-018, ADR-002, ADR-019 의 표시 없는 대체는 phase 06 이 다룬다.

### 2. 구조와 흐름 문서

| 지금 있는 곳 | 찾을 글 | 현재 사실과 증명 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/backend/conversation.md` | `지금은 끝에서 ` 와 `save` | 실제는 `touchSession` 으로 두 칸만 고친다 | 「지금은」 문장을 지우고 까닭 문장만 남긴다 |
| `docs/backend/mcp-caller.md` | 이 길을 쓰는 MCP 도구를 넷으로 적은 줄 | 실제는 여섯이다. 같은 절 앞쪽이 이미 같은 사실을 말한다 | 그 줄을 지운다 |
| `docs/frontend/structure.md` 의 화면 표 | 표에 `/admin/people`, `/connections` 가 없다 | `web/src/app` 에 `admin/people`, `connections`, `connections/[id]` 가 있다. 지금 `web/src/app` 의 경로를 다시 읽어 표와 비교한다 | 빠진 경로를 표에 더한다 |
| `docs/backend/packages.md` 의 패키지 표 | 표에 `connector`, `shared/config`, `shared/util` 이 없다 | `backend/src/main/java` 의 패키지 목록 | 표에 더한다. `shared` 의 기존 위반이 기준 파일에 있다는 한 줄을 적는다 |
| `docs/backend/agent.md`, `docs/backend/people.md` 외 | `셸·파일` | 그룹 공개에서 막는 도구는 여섯이고 `session_search` 가 든다. 코드의 막는 toolset 목록을 읽는다 | 처음 나오는 한 곳에 여섯을 풀어 쓰고 나머지는 그 자리를 가리킨다 |
| `docs/backend/people.md` | `아무것도 남지 않는다` | Hermes 가 응답하지 않으면 행을 만든 뒤 되돌린다. 되돌리기가 실패하면 행이 남는다 | 문장을 사실대로 고친다 |
| `docs/flow.md` 「실행이 실패할 때」 의 오류 표 | `HERMES_BINDING_MISSING` | 선언만 있고 던지는 곳이 없다. `git grep HERMES_BINDING_MISSING backend/src/main` 으로 확인한다 | 표에서 뺀다 |
| `docs/frontend/shell.md` 「화면 틀」 의 사이드바 그림 | `관리 ▸` | 사이드바에 「연결」 이 있고 관리 항목의 실제 문구는 「사용자 관리」 다. `web/src/components/shell` 을 읽는다 | 그림을 고친다 |
| `docs/frontend/shell.md` | ``화면 경로마다 `loading.tsx` `` | `loading.tsx` 가 없는 경로가 있다. `test/unit/loading-routes.test.ts` 의 `ROUTE_FRAMES` 가 두기로 한 경로다 | 「서버가 데이터를 읽는 화면 경로」 로 범위를 바로 적는다 |
| `docs/backend/turn-control.md` 「기동할 때 남은 실행 정리」 | 그 절의 그림 | 정리 뒤에 대기 메시지와 위임 결과가 남은 대화를 다시 연다 | 그림 끝에 그 단계를 한 칸 더한다 |
| `docs/frontend/*.md`, `docs/backend/*.md` | 「」 로 인용한 화면 문구 | 인용한 문구 가운데 실제 문자열과 다른 것이 있다. `web/src` 에서 그 문구를 `git grep` 한다 | 실제 문자열과 같으면 둔다. 풀어 쓴 것이면 「」 를 뺀다. 절 이름을 가리키는 「」 는 건드리지 않는다 |
| `docs/prd.md`, `docs/frontend/chat.md`, `docs/backend/agent.md` | `가족용`, `가족 공용` | 용어 표가 그룹 단위에 쓰지 않기로 한 말이다 | 「그룹용」, 「그룹 공용」 으로 바꾼다. 「가족이 쓰는 화면」 같은 제품 설명과 ADR 본문은 그대로 둔다 |
| `docs/backend/packages.md`, `docs/backend/skill.md` | `사용자과` | 조사 오류다 | `사용자와` 로 고친다 |

### 3. 저장 모델 문서

| 지금 있는 곳 | 찾을 글 | 현재 사실과 증명 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/backend/schema/execution.md` 「agent_execution」 | 표에 `cost_mode`, `context_omitted_items`, `retry_of_execution_id` 가 없다 | 마이그레이션과 엔티티 | 세 줄을 더한다 |
| `docs/backend/schema/users-agents.md` 「agent」 | 표에 `flow`, `connector_managed`, `connector_attachments` 가 없다 | 마이그레이션과 엔티티 | 세 줄을 더한다 |
| `docs/backend/schema/execution.md` 「execution_event」 | `event_type` 목록에 `PROVIDER_SWITCHED` 가 없다 | enum 에 값이 있고 쓰는 곳은 없다 | 「옛 실행에만 남은 값」 으로 더한다 |
| `docs/backend/schema/README.md` 「모델 단계와 재조회」 | 칸 여덟이 이 절의 산문에만 있다 | `model_tier_definition`, `model_tier_group_setting`, `subagent_usage_job` 은 자기 절이 없고, `app_user`, `conversation`, `agent_execution` 의 새 칸은 각 표에 없다 | 표 셋은 `##` 절을 만들어 `users-agents.md`(`model_tier_definition`, `model_tier_group_setting`)와 `execution.md`(`subagent_usage_job`)에 둔다. 칸은 각 표의 줄로 옮긴다. `README.md` 에는 규칙 문장(복사 시점, null 의 뜻, cascade 를 더하지 않음)만 남긴다 |
| `docs/backend/schema/README.md` | 머리말 | 유일 제약과 FK 를 일부 표만 적었다 | 「색인은 마이그레이션이 갖고 이 문서는 유일 제약과 FK 만 적는다」 를 머리에 둔다. 마이그레이션에서 유일 제약과 FK 를 읽어 빠진 표에 더한다 |
| `docs/backend/schema/*.md` | 감사 뒤에 생긴 마이그레이션 | `backend/src/main/resources/db/migration` 의 가장 큰 번호까지 읽는다 | 문서에 없는 표와 칸이 있으면 더한다 |

### 4. 제품 문서와 모델 단계 문서

| 지금 있는 곳 | 찾을 글 | 현재 사실과 증명 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/prd.md` 의 확인 방법 표 | 제안, collection, 판에 관한 세 줄 | 제안은 `PROPOSED` 로 저장되고 실리지 않는다. collection 을 고치는 경로와 판을 읽는 API 가 없다. `memory` 패키지의 controller 를 읽는다 | 첫 줄은 「실리지 않는다」 로 고친다. 나머지 둘은 지금 코드에 경로가 있는지 다시 보고, 없으면 「화면과 경로는 아직 없다」 를 적는다 |
| `docs/prd.md` 「아직 정하지 않은 것」 | 사진 첨부 | 구현이 끝났다 | 그 줄을 지우고 범위 표에 한 줄 더한다 |
| `docs/model-tiers.md` | 구현 지시 말투와 실측 기록 | 계약 문서에 「~한다」 는 지시와 날짜 붙은 실측이 섞였다 | 지시 문장은 사실 문장으로 고치거나 지운다. Hermes 의 동작을 실측한 문단은 `docs/hermes/delegation.md` 의 맞는 절로 옮기고 링크한다 |

### 5. Hermes 문서

| 지금 있는 곳 | 찾을 글 | 현재 사실과 증명 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/hermes/README.md` | `hermes -p <member> login` | 용어 표가 사람 자리에 쓰지 않는 말이다 | `<profile>` 로 바꾼다 |
| `hermes/README.md` 와 `docs/hermes/profiles.md` 의 대시보드 plugin 경로 표 | `model-defaults` 가 표에 없다 | `hermes/plugins/dashboard-profile-api/__init__.py` 가 여는 경로 목록 | plugin 이 여는 경로를 코드에서 읽어 표와 비교하고 빠진 줄을 더한다 |
| `docs/hermes/profiles.md` | `POST /api/profiles` 의 본문 키를 둘로 적은 줄 | plugin 은 키 셋을 받는다 | 받는 키와 Control Plane 이 보내는 키를 나눠 적는다 |
| `docs/hermes/runs-api.md`, `docs/hermes/README.md` | `MVP 는 제출과 조회만 쓴다`, `plugin 없이 성립한다` | 둘 다 지금 사실이 아니다. 중지와 steer 를 쓰고 plugin 둘이 있다 | 두 문장을 지운다 |
| `docs/hermes/README.md`, `docs/adr/ADR-002-*.md` | 이 저장소에 없는 스크립트 파일 이름 | `git ls-files scripts` 에 그 이름이 없다 | 이름을 빼고 「운영 저장소의 검사」 로 적는다 |
| `docs/hermes/kanban.md` | 배포본 버전을 현재형으로 적은 줄, `같은 날 같은 환경` | 다른 문서의 버전과 다르고, 가리키는 앞 문장이 없다 | 조사한 날짜와 버전을 그 문장에 붙인다. 문서 안에 적힌 조사 날짜를 쓴다. 없으면 `git log --follow` 의 첫 커밋 날짜를 쓴다 |
| `docs/hermes/README.md` 의 색인 | `mcp-profile-credentials.md` 가 없다 | 저장소 어디에서도 링크하지 않는다 | 색인에 한 줄 더한다. phase 01 이 만든 `fos-ctx.md`, `skills.md` 도 색인에 있는지 본다 |

### 6. ADR 본문의 사실 오류

ADR 은 결정의 기록이라 본문을 다시 쓰지 않는다. 아래 한 문장씩만 고친다.

| 파일 | 찾을 글 | 현재 사실 | 고칠 내용 |
| --- | --- | --- | --- |
| `docs/adr/ADR-011-*.md` | `취소하는 경로가 없다` | 중지 경로가 있다 | 그 문장 뒤에 ADR-021 링크 한 줄을 더한다 |
| `docs/adr/ADR-004-*.md` | `바인딩의 모델 하나로` | 환산은 실행이 돈 모델로 한다 | 문장을 고친다 |
| `docs/adr/ADR-021-*.md` | 같은 문서의 앞쪽 결정과 어긋나는 `output` 서술 | 앞쪽이 맞다 | 어긋난 문장에서 `output` 부분을 뺀다 |
| `docs/adr/ADR-023-*.md` | 스위치를 네이티브 원소로 적은 줄 | 실제는 `button` 이다 | 「스위치」 를 뺀다 |
| `docs/adr/ADR-014-*.md` | 근거 링크가 대체된 ADR-016 | ADR-016 은 superseded 다 | `../hermes/delegation.md` 로 바꾼다 |
| `docs/adr/ADR-032-*.md`, `docs/adr/ADR-037-*.md` | ``앞으로의 `agent_*` `` | 그 도구는 이미 있다 | 「앞으로의」 를 지운다 |
| `docs/adr/ADR-040-*.md` | 틀린 문장을 두고 정정을 덧붙인 인용 블록 | 인용 블록 뒤에 빈 줄이 없어 본문이 인용 안에 그려진다 | 정정한 내용으로 한 문단을 쓴다 |
| `docs/adr/ADR-053-*.md` | 근거 링크가 ADR-016 | 위와 같다 | ADR-017 로 바꾼다 |

### 7. 프롬프트

`.github/workflows/code-review-prompt.txt` 에서 `status` 가 `completed` 인 `tasks/` 파일을 리뷰 제외 대상으로 적은 줄을 지운다. 끝난 계획서는 지우므로 그런 파일이 남지 않는다.
`test/unit/review-workflow.test.ts` 가 그 줄을 단언하면 테스트도 맞춘다.

## 검증

```bash
# cwd: 저장소 root
# 1. 고친 글이 남지 않았다. 출력이 없어야 한다
git grep -n '지금 읽기만 한다\|POST /api/admin/people\|<member>\|사용자과\|MVP 는 제출과 조회만' -- docs hermes/README.md

# 2. 링크와 앵커. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs

# 3. 문서 경로 검사와 프롬프트 테스트, 공개 정보 검사
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

기대값: 1번 출력 없음. 2번 `BROKEN_LINK`, `BROKEN_ANCHOR` 0건. 3번 종료 코드 0.
`node --test` 는 `test/unit/doc-references.test.ts` 와 `test/unit/review-workflow.test.ts` 를 포함한다.

결과 보고에 표를 남긴다. 발견마다 「고쳤다」, 「이미 고쳐져 있었다」, 「코드가 바뀌어 건너뛰었다」, 「판단이 필요해 남겼다」 가운데 하나와 까닭이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/*.md` | 수정 |
| `docs/backend/*.md` | 수정 |
| `docs/backend/schema/*.md` | 수정 |
| `docs/frontend/*.md` | 수정 |
| `docs/hermes/*.md` | 수정 |
| `docs/adr/ADR-*.md` | 수정 |
| `hermes/README.md` | 수정 |
| `.github/workflows/code-review-prompt.txt` | 수정 |
| `test/unit/review-workflow.test.ts` | 수정 |
