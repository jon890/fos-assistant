## ADR-20261101 / model-setting: 그룹에 모델 설정 하나를 두어 모든 에이전트가 따르고, 사용자는 ChatGPT 의 Thinking 슬라이더 칸으로만 고른다

- **status**: `accepted`
- Date: 2026-11-01
- 아직 일부만 구현했다. 관리자가 단계별 모델을 그룹 목록에서 고르는 칸만 들어갔고, 나머지는 `tasks/` 의 `model-unify` 계획이 단계별로 옮긴다.
- ADR-030 의 「대화가 provider 와 모델, effort 를 직접 고른다」 와 ADR-054 의 「에이전트 기본 모델」 을 대체한다. 대체는 그 부분을 지우는 계획이 머지될 때 적용되고, ADR-054 의 모델 숨김은 그대로다.

### 결정

**그룹에 모델 설정 하나만 둔다. 관리자가 슬라이더 칸마다 실제로 도는 모델과 추론 강도를 그룹 목록에서 고르고, 모든 에이전트와 모든 사용자가 그 설정을 따른다.**

| 대상 | 정한 것 |
| --- | --- |
| 그룹 목록 | 켜진 모든 에이전트 profile 목록의 교집합이다. 모두에게 있는 provider 와 모델만 남는다. `GroupModelCatalogService` 가 만든다(사용자 결정 2026-11-01) |
| 목록을 못 읽을 때 | 한 profile 이라도 목록을 한 번도 읽지 못했으면 그룹 목록을 만들지 않는다. 관리자 화면은 저장된 값만 보이고 비우기만 받는다 |
| 슬라이더 칸 | ChatGPT 의 Thinking 슬라이더를 그대로 옮긴다. `Instant`, `Medium`, `High`, `Extra High`, `Pro` 의 다섯 칸이고 이 순서다. 칸 이름도 옮기지 않고 그대로 쓴다(사용자 결정 2026-11-01) |
| 칸의 값 | 관리자가 칸마다 provider, 모델, effort 를 그룹 목록에서 고른다. 비운 칸은 사용자 슬라이더에 보이지 않는다. ChatGPT 가 요금제마다 칸 수를 달리 보이는 것과 같다 |
| 기본 칸 | 관리자가 그룹 기본 칸을 정한다. 사용자가 슬라이더에서 고른 칸은 그 사용자의 다음 새 대화에도 남는다. ChatGPT 가 고른 thinking 시간을 다음 대화에 남기는 것과 같다 |
| 자동 전환 | 두지 않는다. ChatGPT 는 2026-09-14 에 Instant 에서 Thinking 으로의 자동 전환을 없앴다 |
| 에이전트 기본 모델 | 없앤다. 모든 에이전트가 그룹 설정을 따른다(사용자 결정 2026-11-01) |
| 사용자의 개별 선택 | 없앤다. `CUSTOM` 모드와 사용자 화면의 provider, 모델, effort 고르기를 지운다(사용자 결정 2026-11-01) |
| 새 테이블 | 만들지 않는다. 칸 정의는 `model_tier_definition`, 기본 칸은 `model_tier_group_setting` 과 `app_user.model_default_tier` 를 그대로 쓴다 |

기존 값은 이렇게 옮긴다.

| 기존 값 | 옮기는 곳 |
| --- | --- |
| 단계 `FAST`, `BALANCED`, `DEEP` | `INSTANT`, `MEDIUM`, `HIGH`. `EXTRA_HIGH` 와 `PRO` 는 빈 칸으로 시작한다 |
| 그룹 기본 단계, 사용자 기본 단계, 예약 작업의 단계 | 위와 같은 칸 |
| 대화의 `TIER` 선택 | 위와 같은 칸 |
| 대화의 `DEFAULT` 와 `CUSTOM` 선택 | 선택 없음. 사용자와 그룹 기본 칸으로 돈다. 직접 고른 모델과 effort 의 짝은 칸 하나에 맞춰지지 않아 가장 가까운 칸을 고를 근거가 없다 |
| 에이전트 기본 모델 | 옮기지 않는다. 관리자가 그룹 설정에서 다시 정한다 |
| 실행 기록의 단계 이름 | 바꾸지 않는다. 그때 고른 값의 기록이다. 화면이 옛 이름도 그린다 |

### ChatGPT 모델 선택 조사

2026-11-01 에 공개 자료로 확인했다. 출처마다 다른 것은 가장 최근 공식 출처인 OpenAI 도움말 「GPT-6 and other models in ChatGPT」 를 따랐다.
도움말 페이지는 이 조사 환경에서 직접 열리지 않아(HTTP 403) 검색 결과에 실린 본문 요약으로 확인했다.

| 무엇 | 지금 ChatGPT |
| --- | --- |
| 고르는 모양 | composer 의 모델 단추를 누르면 Thinking 슬라이더가 열린다. 단추 두 개 사이의 토글을 단계형 조절로 바꾼 것이다 |
| 칸 | `Instant`, `Medium`, `High`, `Extra High`, 그리고 별도 모델인 `Pro`. Plus 는 `Instant`, `Medium`, `High` 만 보인다 |
| 칸 이름의 내력 | 2026-06-10 에 `Standard` 를 `Medium`, `Extended` 를 `High`, `Heavy` 를 `Extra High` 로 바꾸고 `Light` 를 없앴다 |
| 기본값 | 공식 출처가 기본 칸을 밝히지 않는다. 2025-09 에 thinking 시간 토글을 낼 때 `Standard`(지금의 `Medium`)를 기본으로 두었고, 고른 값은 다음 대화에도 남았다 |
| 자동 | 2026-03-17 에 「Auto」 를 Configure 안의 「Auto-switch to Thinking」 으로 옮겼고, 2026-09-14 에 Plus 와 Pro 에서, 2026-10-28 에 Enterprise 와 Edu 에서 자동 전환을 없앴다 |
| 개별 모델 | 슬라이더 아래 보조 목록이나 Configure 안에 숨긴다. 옛 모델(legacy)도 그 안에 있다 |
| 관리자 | 업무용 요금제의 workspace 관리자는 구성원이 고를 수 있는 칸을 제한할 수 있다 |

출처:

- OpenAI 도움말, GPT-6 and other models in ChatGPT: <https://help.openai.com/en/articles/11909943-gpt-52-in-chatgpt>
- OpenAI 도움말, ChatGPT release notes: <https://help.openai.com/en/articles/6825453-chatgpt-release-notes>
- OpenAI 도움말, ChatGPT Enterprise and Edu release notes: <https://help.openai.com/en/articles/10128477-chatgpt-enterprise-and-edu-release-notes>
- OpenAI, thinking 시간 토글 공지(2025-09): <https://x.com/OpenAI/status/1968395215536042241>
- TechRadar, thinking 시간 토글 설명(2025-09): <https://www.techradar.com/ai-platforms-assistants/chatgpt/you-can-now-toggle-gpt-5s-thinking-time-for-faster-or-smarter-answers-heres-how-to-do-it>
- AI Toolbox, 요금제별 슬라이더 칸(제3자): <https://www.ai-toolbox.co/chatgpt-models/chatgpt-reasoning-modes-instant-thinking-pro-2026>
- Layer3 Labs, 모델 선택 위치와 보조 목록(제3자): <https://www.layer3labs.io/guides/chatgpt-model-picker-missing>
- Devicebase, 2026-03-17 모델 선택 변경 요약(제3자): <https://devicebase.net/en/openai-chatgpt/updates/updates-to-the-model-picker-in-chatgpt/8za>

### 맥락

사용자는 2026-11-01 에 세 가지를 요청했다.
관리자 「모델」 화면의 단계별 모델을 문자열로 받지 말고 실제로 도는 모델에서 고르게 할 것, 에이전트마다 다른 목록을 하나로 통일할 것, 관리자가 한 번 정하면 에이전트 모델도 바뀌게 할 것이다.
사용자도 슬라이더로 직접 바꾸되 칸과 이름, 순서는 ChatGPT 를 따르고, 개별 모델 고르기는 없애기로 했다.

지금은 모델 목록을 profile 마다 Hermes 가 답한다. profile 마다 인증된 provider 가 달라 목록이 다르다.
그룹 단계 정의와 에이전트 기본 모델이 따로 있어서, 단계를 비운 대화는 에이전트마다 다른 모델로 돈다.
Hermes core 는 고치지 않는다(ADR-001).

### 대안 기각

- **대표 profile 하나의 목록을 그룹 목록으로 쓴다**: 넓지만 다른 profile 에서 인증되지 않은 provider 를 고를 수 있다. 그 에이전트의 실행은 Hermes 가 거절해 실패한다.
- **합집합을 쓴다**: 위와 같은 까닭으로 실패하는 실행이 생긴다.
- **홈서버에서 모든 profile 의 provider 인증을 맞춘다**: 운영 작업이 생긴다(사용자 결정 2026-11-01 로 하지 않는다). 교집합이 좁아지면 그때 인증을 맞추는 일을 후속으로 검토한다. 맞추면 교집합이 저절로 넓어진다.
- **우리 이름(빠르게, 균형, 깊게)을 지킨다**: 사용자가 ChatGPT 의 칸을 그대로 옮기기로 했다. 가족이 다른 서비스에서 쓰던 말과 같아진다.
- **에이전트 기본 모델을 그룹 설정의 덮어쓰기로 남긴다**: 「한 번 정하면 모든 에이전트가 따른다」 와 맞지 않고, 숨김 판정과 목록 대조가 에이전트마다 갈리는 분기가 계속 남는다.
- **`CUSTOM` 을 접힌 고급 칸으로 남긴다**: 사용자가 개별 선택을 없애기로 했다.

### 결과

- 얻는 것: 관리자가 정한 모델이 모든 에이전트에서 돈다. 관리자 칸은 목록에 있는 값만 받아 오타로 실행이 실패하지 않는다. 사용자는 칸 하나만 고른다.
- 감당할 것:
  - 교집합이라 profile 하나에만 인증된 provider 는 고르지 못한다. 목록이 좁으면 홈서버에서 인증을 맞춘다.
  - 에이전트 하나가 목록을 한 번도 읽지 못하면 관리자는 그동안 새 모델을 고르지 못한다. 저장된 값은 그대로 돈다.
  - 직접 고른 모델로 대화하던 사용자는 이관 뒤 기본 칸으로 돈다.
  - `Pro` 는 ChatGPT 에서 다른 모델이다. 우리도 관리자가 그 칸에 다른 모델을 둘 수 있을 뿐이고 요금제 구분은 없다.
