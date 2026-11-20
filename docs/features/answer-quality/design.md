# 답변 품질 기술 설계

스펙: [`spec.md`](spec.md)(확인 2026-11-18).
이 문서는 PR1(REQ1~REQ7)의 설계다. PR2 부터는 그 PR 이 자기 절을 더한다.

## upstream 소스로 확인한 사실

2026-11-18 에 Hermes v0.23.2 tag 소스를 읽었다. 운영 왕복으로는 확인하지 않았다. 같은 내용을 [`hermes/docs/hermes-contract.md`](../../../hermes/docs/hermes-contract.md#웹-검색-서비스) 의 「웹 검색 서비스」 로 옮겼고 원본은 그쪽이다.

| 무엇 | v0.23.2 의 동작 | 근거 |
| --- | --- | --- |
| 검색 서비스 고르기 | profile 설정 `web.search_backend` 가 검색을, `web.extract_backend` 가 본문 읽기를 정한다. 둘 다 없으면 `web.backend`, 그것도 없으면 key 가 있는 서비스, key 없이 되는 서비스 순으로 스스로 고른다 | `tools/web_tools.py` 의 `_get_search_backend`, `_get_extract_backend` |
| OpenAI 내장 검색 | `web.search_backend: openai-native` 이고 요청이 ChatGPT 구독 경로(Codex backend)로 갈 때, 모델에 주는 `web_search` 함수를 OpenAI 서버가 실행하는 `{"type": "web_search"}` 로 바꿔 보낸다. 검색만 맡고 본문 읽기는 다른 서비스가 맡는다 | `agent/transports/codex.py`, `plugins/web/openai_native/provider.py` |
| 내장 검색의 사건 | 서버에서 돈 검색은 응답의 `web_search_call` 항목으로 오고 Hermes 는 이것을 도구 호출로 다루지 않는다. `tool.started`, `tool.completed` 가 오지 않는다. 답의 출처 주석(`url_citation`)도 읽지 않는다. 출처는 모델이 답 글에 쓴 링크로만 남는다 | `agent/codex_responses_adapter.py` 의 `_SERVER_SIDE_TOOL_CALL_TYPES` |
| 내장 검색과 다른 경로 | 내장 검색을 고른 채 ChatGPT 구독 경로가 아닌 모델로 돌면 `web_search` 가 오류를 돌려준다. 다른 서비스로 넘어가지 않는다 | `plugins/web/openai_native/provider.py` 의 `search` |
| 날짜 | 시스템 프롬프트에 대화를 시작한 날짜와, 문맥을 마지막으로 다시 만든 때의 오늘 날짜가 든다. turn 마다 갱신되지 않는다 | `agent/system_prompt.py` |

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `ContextAssembler` 의 공통 답변 지침에 「최신 정보와 출처」 절을 더한다. 실행 종류가 「대화」 인 실행에만 넣는다(아래 「실행 종류」). 절은 「`web_search` 도구가 있으면」 으로 시작하는 한 벌의 글이다(DECISION1) |
| REQ2 | 실행 종류가 「대화」 인 실행의 turn 전용 지시(`ChatTurnLifecycle` 의 `AskFormat.appendTo` 자리, `AgentRunner` 의 `appendInstruction` 자리)에 그 turn 을 시작한 날의 날짜와 요일 한 줄을 더한다. `Clock` 과 `Asia/Seoul` 로 만든다. 실행 기록의 지문은 두 곳 모두 이 자리 앞에서 뜨므로 바뀌지 않는다. 예약과 살펴보기 실행에는 넣지 않는다 |
| REQ3 | 같은 절이 문장 옆 링크와 답 끝의 「출처」 목록, 주소를 지어내지 않기를 시킨다. 화면은 바꾸지 않는다. 답 아래 원문 열람 목록(`SourceReadSummaries`)도 그대로다 |
| REQ4 | 같은 절이 「검색이 실패했거나 `web_search` 도구가 없으면 답 첫머리에 확인하지 못했다는 것과 기준 시점을 밝힌다」 를 시킨다 |
| REQ5 | 코드는 검색 서비스를 고르지 않는다. 이 PR 은 profile 틀(`hermes/profile-template/config.yaml.template`)에 `web` 절의 자리와 두 값의 뜻을 주석으로만 둔다. 값은 평가 뒤 후속 PR 이 넣는다(아래 「PR1 의 순서」). 본문 읽기 실패는 운영에서 오류 글을 확인해 `web.extract_backend` 로 고친다 |
| REQ6 | 코드 변경이 없다. 관리자가 에이전트 도구 화면에서 켠다(스펙 DECISION11). 배포 뒤 확인 항목으로 둔다 |
| REQ7 | `test/model-eval/answer/` 에 질문 세트, 실행 명령, 판정 함수를 둔다. 블로그 사진 평가의 Control Plane 클라이언트(`test/model-eval/blog/api.ts`)를 `test/model-eval/control-plane.ts` 로 옮겨 함께 쓴다 |
| REQ8 | PR2 가 설계한다. PR1 은 이 요구사항의 코드를 만들지 않는다 |
| REQ9 | PR2 가 설계한다 |
| REQ10 | PR2 가 설계한다 |
| REQ11 | PR2 가 설계한다. 내장 검색을 쓰는 동안 검색어 사건이 오지 않는다는 사실은 위 표에 남겼다 |
| REQ12 | PR3 이 설계한다 |
| REQ13 | PR3 이 설계한다. PR1 의 평가 명령은 질문 하나에 메시지를 여럿 보낼 수 있는 모양으로 두어 PR3 이 여러 turn 질문을 더할 수 있게 한다 |
| REQ14 | PR4 가 한다. PR1 의 평가 명령이 모델 칸을 인자로 받는 것이 그 준비다 |
| REQ15 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |
| REQ16 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |
| REQ17 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |
| REQ18 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |
| REQ19 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |
| REQ20 | PR5 가 한다. 아래 「PR5 답 모양」 의 요구사항 연결 |

## 책임과 경계

새 모듈을 만들지 않는다. 분리 신호(쓰는 사람, 배포 환경, 데이터 생명주기, 거꾸로 읽기)에 걸리는 것이 없다. 지침은 지금 지침의 주인이 갖고 평가는 지금 평가의 자리에 둔다.

| 자리 | 맡는 것 |
| --- | --- |
| `context/application/ContextAssembler` | 「최신 정보와 출처」 절의 글과, 어느 실행 종류에 넣는지. `withResponseInstructions` 가 받는 값을 「기억 지침을 넣는가」 하나에서 실행 종류로 바꾼다 |
| `chat/turn/application/ChatTurnRunner`, `orchestration/application/AgentRunner`, `proactive/check/application/ProactiveCheckInput` | 자기 실행의 종류를 정해 `ContextAssembler` 에 넘긴다. 글을 직접 갖지 않는다 |
| `chat/turn/application/ChatTurnLifecycle`, `AgentRunner` | 종류가 「대화」 일 때 turn 전용 지시에 날짜 한 줄을 붙인다. 날짜 글을 만드는 것은 `context/application` 의 작은 클래스 하나가 맡아 두 곳이 같은 글을 쓴다 |
| `hermes/profile-template/config.yaml.template` | `web` 절의 자리 |
| `hermes/docs/hermes-contract.md` | 위 「upstream 소스로 확인한 사실」 |
| `test/model-eval/answer/` | 질문 세트(`questions.json`), 명령(`run.ts`), 판정(`score.ts`)과 그 단위 테스트 |
| `test/model-eval/control-plane.ts` | 두 평가가 함께 쓰는 Control Plane 클라이언트. `test/support/` 가 이미 있어 `support` 폴더를 하나 더 만들지 않는다 |
| `scripts/ci/ci-paths.mjs` | `test/model-eval/` 아래의 단위 테스트를 블로그 평가와 같은 규칙(언제나 도는 unit 만)으로 둔다. 지금 규칙은 `blog` 폴더만 가리킨다 |
| `docs/features/answer-quality.md` | 지금 동작과 평가의 실행 방법, 숫자. `covers:` 에 `test/model-eval/answer/` 를 더한다 |
| `fos-home-infra` | 기존 profile 에 `web` 값 넣기, 기본 에이전트에 `web` 켜기, 평가를 돌리는 접속 값 |

`web` 이 켜졌는지를 Control Plane 이 turn 마다 읽지 않는다. 켜진 toolset 은 Hermes 에만 있고(`HermesToolsetClient.readEnabled`), turn 마다 읽으면 답이 시작되기 전에 Hermes 왕복이 하나 더 든다. 그래서 절을 도구 유무에 따라 갈라 넣지 않고 한 벌의 글이 두 경우를 모두 말한다.

## 인터페이스와 데이터

API, 사건, 테이블은 바뀌지 않는다.

**실행 종류.** 값은 셋이다.

| 종류 | 넣는 것 | 드는 실행 |
| --- | --- | --- |
| 대화 | 기억 지침, 「최신 정보와 출처」 절, 날짜 줄 | 사용자 대화 turn(`Fresh`, `Regenerate`, `Mention`), 사람이 있는 트리의 결과를 전하는 `DelegationResults`, 사람이 있는 트리의 위임받은 자식 |
| 예약 | 기억 지침(지금과 같다) | `Scheduled` turn, `fromUnattended` 가 참인 `DelegationResults`, 예약 트리의 위임받은 자식 |
| 살펴보기 | 없음(지금과 같다) | `ProactiveCheck` turn, 살펴보기 트리의 자식, `ProactiveCheckInput` 의 지문 다시 계산 |

`AgentRunner` 는 지금 살펴보기 트리만 구분한다(`proactiveChecks.existsByRootExecutionId`). 예약 트리의 자식은 루트 실행이 사람이 없는 실행인지로 구분한다. 그 판정은 `SessionOwnerResolver.unattendedTree` 가 이미 쓰는 저장소 조회(`countUnattendedIn`)다.
`ProactiveCheckInput` 은 지난 살펴보기와 지문을 비교하므로 반드시 「살펴보기」 를 넘긴다. 다른 종류를 넘기면 지문이 달라져 「문맥이 바뀜」 신호가 늘 켜진다.

Hermes 내장 위임(`delegate_task`)의 자식은 부모의 `instructions` 를 받지 않는다([`hermes-contract.md`](../../../hermes/docs/hermes-contract.md)). 그 자식에는 절도 날짜도 가지 않는다. 그래서 절에 「조사를 하위 작업에 맡기면 찾은 주소를 함께 돌려받아 출처로 쓴다」 를 넣어 부모가 출처를 챙기게 한다. 스펙의 「위임받은 자식」 은 Control Plane 이 지침을 조립하는 자식(`agent_delegate`)을 가리킨다.

**지침 절.** 글의 원본은 `ContextAssembler` 의 상수다. 아래는 담을 뜻이고 문구는 구현이 다듬는다.

- `web_search` 도구가 있으면, 가격·재고·영업시간·일정·뉴스·시세·제품 사양과 출시·제도와 법처럼 시간이 지나면 바뀌는 사실은 기억으로 답하지 않고 검색해 확인한다
- 일반 상식, 글쓰기, 사용자의 개인 사정만으로 답할 수 있는 질문은 검색하지 않는다
- 비교나 계획처럼 한 번 검색으로 모자란 질문은 여러 번 찾고 서로 맞춰 본다
- 검색으로 확인한 문장 옆에 마크다운 링크를 달고 답 끝에 「출처」 목록(제목과 링크)을 둔다. 목록에는 `web_search` 와 `web_extract` 가 돌려준 주소만 쓰고 지어내지 않는다. 검색하지 않았으면 목록을 두지 않는다
- 검색이 실패했거나 결과가 없거나 `web_search` 도구가 없는데 바뀌는 사실을 물으면, 답 첫머리에 검색으로 확인하지 못했다는 것과 아는 정보의 기준 시점을 밝히고 아는 만큼 답한다. 플러그인이나 브라우저 같은 다른 도구로 이번 실행에서 확인한 사실에는 해당하지 않는다
- 조사를 하위 작업에 맡기면 찾은 주소를 함께 돌려받아 출처로 쓴다

**지문과 글자 수.** 절이 공통 답변 지침에 들므로 대화 실행의 `instructions_hash` 와 `context_chars` 가 배포 때 한 번 바뀐다. 날짜 줄은 turn 전용 지시라 둘에 들지 않는다. [`memory.md`](../memory.md) 의 운영 집계가 `context_chars` 에 공통 지침을 함께 센다는 설명은 그대로 맞다.

**평가의 입력과 출력.**

| 무엇 | 모양 |
| --- | --- |
| `questions.json` | 질문마다 id, 범주, 보낼 메시지의 차례, 통과를 보는 방식(출처, 날짜 일치, 보지 않음), 사람 비교 대상인지. 스펙 「평가 세트」 의 스무 문항 |
| `run.ts send` | 질문마다 관리자 계정의 새 대화를 만들어 보내고 끝날 때까지 기다린다. 모델 칸과 결과에 붙일 이름표(검색 서비스 이름 등)를 인자로 받는다. 대화 식별자와 실행 번호만 결과 파일에 적는다 |
| `run.ts score` | 답과 실행 트리를 읽어 질문마다 「출처」 링크 수, 열리는 링크 수, 걸린 시간, 실행 사건의 `web_search` 와 `web_extract` 수와 실패 수를 표로 낸다. 스펙 DECISION10 의 기준과 비교한 통과 여부와, 사람 비교 여덟 문항에 결과를 적을 빈 칸을 함께 낸다 |
| `score.ts` | 네트워크를 쓰지 않는 순수 함수. 답에서 「출처」 절과 링크를 뽑고, 받은 링크 확인 결과와 사건 수로 질문과 세트의 통과를 정한다 |

세는 규칙은 이렇다.

- 답은 기록한 실행이 답한 사용자 메시지 뒤부터 다음 `USER` 메시지 전까지의 비서 메시지 전체다. 다른 실행 번호로 온 자동 turn 도 포함한다. 각 메시지의 출처를 모아 중복을 뺀다(코디네이터 결정 2026-11-20: 사용자가 화면에서 보는 전체 답을 평가한다)
- 도구 수는 그 루트의 실행 트리에서 끝 사건(`TOOL_COMPLETED`)만 센다. 시작 사건을 함께 세면 두 배가 된다. 실패 수는 그 사건의 `failed` 다
- 걸린 시간은 루트 실행의 `latencyMs` 다
- 「오늘 날짜」 문항은 답에 평가를 돌린 날의 날짜(한국 시간)가 들었는지로 본다. 출처 기준의 분모에 넣지 않는다(스펙 DECISION10)
- 옮긴 클라이언트의 타입에 답 본문, 사건 종류와 실패 여부, 걸린 시간을 더한다. API 응답에는 이미 있다(`ChatDtos`, `ExecutionNode`, `ExecutionEventView`)

링크가 열리는지는 `run.ts score` 가 평가를 돌리는 머신에서 주소마다 요청을 보내 본다. 2xx 와 3xx 를 열린 것으로 본다. 자동 요청을 막는 사이트(403, 429)는 「확인 못 함」 으로 따로 세고 분모에서 뺀다.

## PR1 의 순서

1. 운영 Hermes 가 v0.23.2 로 바뀐 것이 확인되면, 이 PR 을 배포하기 전에 평가 명령으로 고치기 전 기준값을 잰다. 평가 명령은 운영 API 만 부르므로 머지 전의 브랜치에서 돌릴 수 있다
2. 이 PR(지침, 날짜, 평가, 틀의 주석, 계약 문서)을 머지하고 배포한다. 관리자가 기본 에이전트에 `web` 을 켠다
3. 평가를 지금 무료 서비스와 내장 검색으로 각각 돌리고 사용자가 여덟 문항을 나란히 본다
4. 후속 PR 이 앞선 값을 profile 틀에 넣고, 그 선택을 ADR 로 남기고, 숫자를 기능 파일에 적는다. 기준에 못 미치면 같은 PR 이 지침 글을 고친다. 스펙 REQ7 의 「PR1 이 끝난 것」 은 이 후속 PR 이 머지된 때다

## 실패와 일관성

- 지침은 모델이 따르지 않을 수 있다. 그래서 통과를 전부가 아니라 스펙 DECISION10 의 기준(열일곱 중 열다섯)으로 본다. 못 미치면 절의 글을 고쳐 다시 돌린다
- 절을 넣는 실행 종류를 잘못 넘기면 먼저 살펴보기에 검색 지시가 든다. 먼저 살펴보기는 읽기 경계가 따로 있어 도구가 열리지는 않지만, 단위 테스트로 종류마다 절이 드는지를 고정한다
- 내장 검색을 고른 동안에는 실행 사건의 검색 수가 0 이다. 평가는 「출처」 링크로 세므로 영향이 없다. 화면의 작업 과정에는 검색어가 보이지 않는다(스펙 DECISION2 가 감수)
- 배포 순서의 제약이 없다. 지침은 Control Plane 만 바꾸고, profile 의 `web` 값은 Control Plane 과 따로 바꾼다
- 평가는 대화를 만들기만 하고 승인 카드를 누르지 않는다. 통과한 대화를 지우는 것과 남기는 것은 블로그 사진 평가와 같게 한다

## 검증 전략

| ID | 어디서 확인하나 |
| --- | --- |
| REQ1, REQ3, REQ4 | backend 단위 테스트: 실행 종류마다 조립한 지침에 절이 들고 빠지는지. 운영: 평가 세트의 숫자 |
| REQ2 | backend 단위 테스트: 고정한 `Clock` 으로 날짜 줄의 글과, 날짜가 달라도 지문이 같은지. 운영: 평가 세트의 「오늘 날짜」 문항 |
| REQ5 | 운영: 두 검색 서비스로 평가를 돌린 숫자와 `web_extract` 실패 수. 계약 문서 대조 |
| REQ6 | 운영: 배포 뒤 확인 항목 |
| REQ7 | `score.ts` 의 단위 테스트(CI 에서 돈다)와 `questions.json` 의 형식 테스트. 운영: 고치기 전 기준값과 배포 뒤 값 |

운영 평가는 운영 Hermes 가 v0.23.2 로 바뀐 것이 확인된 뒤에 돌린다. 고치기 전 기준값은 PR1 을 배포하기 전에 잰다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 검색 도구가 꺼진 실행의 지침 | 스펙 DECISION14(사용자 2026-11-18)를 따른다. 절을 나눠 넣지 않고 한 벌의 글이 「도구가 있으면」 과 「없으면」 을 함께 말한다 | 작성자 | 제품 결정은 스펙이 갖는다. 켜진 toolset 은 Hermes 만 알고, turn 마다 읽으면 모든 답이 Hermes 왕복 하나만큼 늦어지며 그 조회가 실패하면 답도 막힌다 |
| DECISION2 | 날짜를 넣는 자리 | turn 전용 지시 | 작성자 | 공통 지침에 넣으면 `instructions_hash` 가 날마다 바뀌어 지문으로 지침 변경을 찾지 못한다. 질문 카드의 형식 안내가 같은 자리를 쓴다([`chat.md`](../chat.md) 의 「에이전트가 물을 때」) |
| DECISION3 | 절과 날짜를 넣는 실행 | 실행 종류 「대화」 만. 위 「실행 종류」 표가 갖는다 | 작성자 | 스펙 DECISION14. 사람이 없는 트리의 결과를 전하는 turn 은 예약으로 본다. 그 트리의 지침을 바꾸지 않기로 했다 |
| DECISION4 | 검색 서비스 값의 자리 | profile 설정. Control Plane 설정이나 화면을 만들지 않는다 | 작성자 | Hermes 가 profile 설정에서 읽는다. 값 하나를 평가 뒤 한 번 정하는 일이라 화면이 필요 없다 |
| DECISION5 | 평가 클라이언트 | `blog/api.ts` 를 `test/model-eval/control-plane.ts` 로 옮겨 함께 쓴다 | 작성자 | 두 평가가 같은 Control Plane API 를 부른다. 복사하면 한쪽만 고쳐진다. 옮겨서 바뀌는 import 는 `blog/run.ts` 하나다 |
| DECISION7 | Hermes 내장 위임의 자식 | 절과 날짜를 넣지 못하는 것을 감수하고, 부모의 절이 주소를 돌려받게 한다 | 작성자 | Hermes 가 부모의 지침을 자식에게 넘기지 않는다. Hermes core 를 고치지 않는다([ADR-001](../../adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)) |
| DECISION8 | 검색 서비스 값을 넣는 PR | 평가 뒤의 후속 PR | 작성자 | 값은 배포 뒤 평가로 정해진다. 이 PR 머지 때는 정해지지 않았다 |
| DECISION6 | 링크 확인에서 막힌 사이트 | 「확인 못 함」 으로 따로 세고 분모에서 뺀다 | 작성자 | 자동 요청을 막는 것은 링크가 틀렸다는 뜻이 아니다. 수는 보고서에 남겨 사람이 본다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 평가를 운영 왕복으로 돌리고 규칙으로 판정 | architecture | 필요 없음. [ADR-20261020 / blog-photo-eval](../../adr/ADR-20261020-blog-photo-eval.md) 의 틀을 그대로 따른다 | 기능 파일 [`answer-quality.md`](../answer-quality.md) |
| 검색 서비스 선택 | operations | 평가 숫자가 나온 뒤 그 결과와 함께 ADR 로 남긴다. PR1 머지 때는 값이 정해지지 않았다 | 계약 문서와 기능 파일 |

## PR5 답 모양

스펙 REQ15부터 REQ20 과 DECISION20부터 DECISION30(확인 2026-11-20)의 설계다. API, 사건, 테이블은 바뀌지 않는다.

### 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ15 | `ContextAssembler.SEARCH_INSTRUCTIONS` 의 「출처」 문장을 `### 출처` 와 `- [기관 · 문서 이름](주소)` 꼴, 주소를 글자로 쓰지 않기, 목록 안 한 번으로 바꾼다 |
| REQ16 | `RESPONSE_INSTRUCTIONS`(모든 실행 공통)에 답 모양 규칙을 더한다. 표 구분 줄 규칙과 예시는 그대로 둔다 |
| REQ17 | 채팅 마크다운 부품에 글 안의 줄바꿈 한 번을 `br` 로 바꾸는 작은 rehype 플러그인을 더한다. 코드 안과 블록 사이는 건드리지 않는다. 흐림 플러그인이 글자의 원문 위치로 구간을 찾으므로 그 뒤에 돈다 |
| REQ18 | 채팅 마크다운 부품에 제목, 인용, 구분선, 굵은 글자 요소와 본문 글자 토큰을 더한다. 작업 과정 블록은 `density="compact"` 로 지금 크기와 간격을 쓴다. 출처 절은 rehype 플러그인이 최상위의 글이 「출처」 인 제목부터 다음 제목 앞까지를 `section` 하나로 감싸고 그 `section` 에 모양을 준다 |
| REQ19 | 개발 문서의 `MermaidBlock` 과 `mermaidRenderId` 를 공용 자리로 옮겨 두 마크다운 부품이 함께 쓴다. remark 플러그인이 닫는 줄이 없는 코드 블록에 `data-open` 을 달고, 채팅 부품은 `streaming` 이면서 그 표시가 있으면 코드로 둔다. 부품 묶음은 `density` 와 `streaming` 으로 고정해 흐르는 동안 그림이 다시 붙지 않게 한다 |
| REQ20 | `score.ts` 에 형식 판정 순수 함수를 더하고 `QuestionScore` 에 선택 칸으로 둔다. 보고서 표와 요약에 낸다. 통과 판정(`scoreSet` 의 `outcome`)은 이 칸을 읽지 않는다 |

### 책임과 경계

| 자리 | 맡는 것 |
| --- | --- |
| `context/application/ContextAssembler` | 두 지침 글 |
| `web/src/components/ui/mermaid-block.tsx`, `web/src/lib/mermaid.ts` | 옮긴 Mermaid 그리기와 id 만들기. 개발 문서와 채팅이 함께 쓴다 |
| `web/src/components/chat/markdown.tsx` | 채팅 마크다운 부품, 플러그인 순서 |
| `web/src/components/chat/markdown-components.tsx` | 요소마다의 모양과 `density` 묶음, mermaid 와 코드 블록 고르기 |
| `web/src/components/chat/code-block.tsx` | 코드 블록 칠하기. 파일 길이 기준을 지키려고 옮겼다 |
| `web/src/components/chat/markdown-plugins.ts` | 줄바꿈 한 번, 출처 절 감싸기, 닫히지 않은 블록 표시. node 테스트가 읽도록 `.ts` 로 둔다 |
| `web/src/components/chat/answer-body.tsx` | 흐르는 중인지를 마크다운 부품에 넘긴다 |
| `web/src/app/globals.css` | 본문 글자 토큰(`--text-answer`) |
| `test/model-eval/answer/score.ts` | 형식 판정과 보고서 칸 |
| `docs/features/answer-quality.md`, `docs/features/chat.md` | 지금 동작 |

새 의존은 더하지 않는다. `remark-breaks` 는 같은 일을 하는 스무 줄 남짓이라 직접 쓴다. Mermaid 는 이미 의존에 있다.

### 실패와 일관성

- Mermaid 원문이 틀리면 원문 코드와 「그림을 그리지 못했어요」 가 보인다(지금 개발 문서와 같다). `securityLevel: "strict"` 와 react-markdown 의 HTML 불신 기본값(rule-57)은 그대로다
- 흐르는 중에 닫히지 않은 블록을 그리려 하면 매 글자마다 실패한다. 그래서 닫는 줄이 없으면 그리지 않는다. 답이 끝나면 닫는 줄이 없어도 그려 본다(스펙 REQ19)
- 지침 글이 바뀌어 `instructions_hash` 가 한 번 바뀐다. 같은 날 같은 기억이면 다시 같아진다
- 줄바꿈 한 번을 살리면 이전 답에서 문단 안에서 줄을 감은 곳도 줄이 바뀐다. 스펙 DECISION21 이 감수했다

### 검증 전략

| ID | 어디서 확인하나 |
| --- | --- |
| REQ15, REQ16 | backend 단위 테스트(`ContextAssemblerTest`): 지침에 출처 꼴 문장과 답 모양 규칙이 들고, 예약 작업과 먼저 살펴보기에도 답 모양이 든다 |
| REQ17, REQ19 | web node 테스트(`chat-markdown-plugins.test.ts`): 운영 답의 출처 블록 모양에 `<br>` 가 들고, 코드와 블록 사이는 그대로이며, 출처 절이 `section` 으로 감싸지고, 닫히지 않은 블록에 `data-open` 이 붙는다 |
| REQ18, REQ19 | 브라우저 테스트(`chat.spec.ts`, 넓은 화면과 휴대폰): 제목 18px, 본문 15px, 출처 절 14px, 줄바꿈 `br`, mermaid 그림과 실패 문구, 그림이 화면 폭 안에 있다 |
| REQ20 | `score.ts` 단위 테스트: 목록 꼴, 맨 주소, 반복, 긴 문단 판정과 보고서 칸 |
| 모두 | 배포 뒤 확인 7: 출처 있는 답 하나를 넓은 화면과 휴대폰에서 보고 평가를 다시 돈다 |
