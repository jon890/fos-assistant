# 웹 화면 한국어 문구 검토표 (결정 반영)

이 표가 plan032 의 정본이다. 기준은 `origin/main` `c4f4335` 의 `web/src` 다. 구현할 때 파일이 옮겨졌으면 문구로 찾는다.

## 사용자 결정 (2026-09-28)

아래 「사용자가 정할 것」 표의 아홉 항목은 모두 **권장안**으로 정해졌다.

| 항목 | 결정 |
| --- | --- |
| provider | 모델 제공사 |
| Hermes API 주소 | 에이전트 연결 주소 |
| Hermes profile | profile |
| 일반 사용자의 오류 안내 | 할 일만 알린다. Hermes, profile, API key 같은 원인은 보이지 않는다 |
| credential 범위, 가족 공유 credential, 전용 credential | AI 계정 사용 범위. 선택지는 「그룹 공유」, 「전용」 |
| 실행 나무 | 작업 과정. 「작업 과정 자세히 보기」, 「작업 과정을 불러오지 못했어요」 |
| Memory | 기억. 절 제목은 「검토할 기억」, 「그룹이 함께 아는 것」, 「나에 대해 아는 것」 |
| 설정 지문 | 설정별 사용량, 설정 차이, 설정 구분값 |
| 사이트 설명 `가족이 함께 쓰는 개인 AI 비서` | 함께 쓰는 AI 비서 |

말투는 해요체로 통일하고, 비서가 사용자에게 하는 말은 높임을 쓴다(「무엇을 도와드릴까요?」). 규칙은 `web/AGENTS.md` 「화면 문구」 절이 갖는다.

## 요약

- 기준: `origin/main` `c4f4335`의 `web/src`.
- 검토 결과는 제안이며, 사용자가 확인한 뒤 코드에 반영합니다.
- 문구 후보 514곳 중 사용자에게 보이는 문구 512곳을 판정했습니다. 바꿀 것 178곳, 그대로 둘 것 287곳, 사용자가 정할 것 47곳입니다.
- 화면에 보이지 않는 내부 예외 2곳은 집계에서 제외했습니다.

### 수집 방법

- `git archive origin/main web/src`로 기준 파일을 분리한 뒤 TypeScript 파서로 `.ts`·`.tsx` 파일의 문자열 리터럴, 템플릿 문자열, JSX 텍스트를 추출했습니다. 한글 음절이 있는 후보를 자동 수집하고 관리자 화면에서 영어로만 보이는 문구 6곳을 추가했습니다.
- 파서의 구문 노드에서 수집했으므로 `//`, `/* */`, JSX 주석은 자동으로 제외됩니다. 템플릿 문자열은 `${…}`를 포함한 한 문구로 세고, JSX에서 동적 값 때문에 나뉜 텍스트 조각은 화면 문맥으로 다시 확인했습니다.
- API 경로가 브라우저에 돌려주는 오류 문자열, `aria-label`, `title`, 페이지 `metadata`, 화면 상태 문구를 포함했습니다. 다른 언어만 들어 있는 문구와 서버에서 생성하는 사용자 입력·모델 답변은 한국어 문구 판정에서 제외했습니다.
- 같은 문구가 여러 위치에 있으면 한 행에 위치를 모두 적었습니다. 빈 상태의 제목과 설명처럼 같은 줄에 두 문구가 있으면 각각 한 곳으로 셌습니다.
- 제외: [web/src/app/c/[conversationId]/page.tsx:24](../../web/src/app/c/[conversationId]/page.tsx#L24)<br>[web/src/components/shell/conversations-provider.tsx:89](../../web/src/components/shell/conversations-provider.tsx#L89). 두 문구는 예외 객체에만 쓰이고 화면이나 API 응답에 그대로 실리지 않습니다.

## 사용자가 정할 것

| 항목과 현재 문구 | 쓰인 곳 | 선택지 | 권장안과 까닭 |
| --- | --- | --- | --- |
| `막힌 provider:`<br>`지난 기록이 쓰는 provider / 모델`<br>``${index + 1}순위 provider``<br>`provider` | [web/src/app/admin/agents/agent-admin-panel.tsx:196](../../web/src/app/admin/agents/agent-admin-panel.tsx#L196)<br>[web/src/components/admin/agent-card.tsx:64](../../web/src/components/admin/agent-card.tsx#L64)<br>[web/src/components/admin/agent-model-list.tsx:67](../../web/src/components/admin/agent-model-list.tsx#L67)<br>[web/src/components/admin/agent-form.tsx:26](../../web/src/components/admin/agent-form.tsx#L26) | 1. 모델 제공사<br>2. provider<br>3. 제공 서비스 | **모델 제공사**. 관리 화면의 provider를 같은 이름으로 부를지 정해야 합니다. |
| `Hermes API 주소`<br>``${agent.name} Hermes API 주소`` | [web/src/components/admin/agent-card.tsx:69](../../web/src/components/admin/agent-card.tsx#L69)<br>[web/src/components/admin/agent-card.tsx:76](../../web/src/components/admin/agent-card.tsx#L76)<br>[web/src/components/admin/agent-form.tsx:25](../../web/src/components/admin/agent-form.tsx#L25) | 1. 에이전트 연결 주소<br>2. Hermes API 주소 | **에이전트 연결 주소**. 관리자가 입력할 주소의 용도를 드러냅니다. |
| `여기서 더하면 그 사람의 Hermes profile 까지 만들어진다. 사용자와 에이전트는 그 사람이 처음 로그인할 때 생긴다.`<br>`모델은 등록할 때 Hermes에서 읽는다.`<br>`Hermes profile`<br>`profile` | [web/src/app/admin/people/people-admin-panel.tsx:81](../../web/src/app/admin/people/people-admin-panel.tsx#L81)<br>[web/src/components/admin/agent-form.tsx:32](../../web/src/components/admin/agent-form.tsx#L32)<br>[web/src/components/admin/agent-card.tsx:63](../../web/src/components/admin/agent-card.tsx#L63)<br>[web/src/components/admin/agent-form.tsx:24](../../web/src/components/admin/agent-form.tsx#L24)<br>[web/src/components/admin/person-form.tsx:29](../../web/src/components/admin/person-form.tsx#L29)<br>[web/src/components/admin/person-list.tsx:27](../../web/src/components/admin/person-list.tsx#L27) | 1. profile<br>2. Hermes profile<br>3. AI 설정 공간 | **profile**. 관리 화면에서 고유한 격리 단위를 부르는 이름을 정해야 합니다. |
| `Hermes에서 모델을 읽지 못했다. profile API 상태를 확인한다.`<br>`아직 이 계정에 연결된 AI 계정이 없다. 관리자에게 Hermes profile 연결을 요청한다.`<br>`연결된 profile 의 API key 가 서버에 준비돼 있지 않다.`<br>`그 이름의 Hermes profile 이 이미 있다. 다른 profile 이름을 쓴다.`<br>`Hermes profile 을 만들지 못했다. 만들던 것은 거뒀으니 같은 이름으로 다시 시도할 수 있다.`<br>`그 profile 이름은 이미 쓰고 있다. 다른 이름을 쓴다.`<br>`Hermes 런타임에 연결하지 못했다.` | [web/src/components/error-message.ts:5](../../web/src/components/error-message.ts#L5)<br>[web/src/components/error-message.ts:6](../../web/src/components/error-message.ts#L6)<br>[web/src/components/error-message.ts:8](../../web/src/components/error-message.ts#L8)<br>[web/src/components/error-message.ts:9](../../web/src/components/error-message.ts#L9)<br>[web/src/components/error-message.ts:10](../../web/src/components/error-message.ts#L10)<br>[web/src/components/error-message.ts:12](../../web/src/components/error-message.ts#L12)<br>[web/src/components/error-message.ts:15](../../web/src/components/error-message.ts#L15) | 1. 일반 사용자에게 조치만 안내<br>2. Hermes와 profile 오류 원인도 표시 | **일반 사용자에게 조치만 안내**. 일반 오류 안내에 내부 제품명과 key 상태를 얼마나 보여 줄지 정해야 합니다. |
| `credential 범위`<br>`가족 공유 credential`<br>`전용 credential` | [web/src/components/admin/agent-form.tsx:29](../../web/src/components/admin/agent-form.tsx#L29)<br>[web/src/components/admin/agent-form.tsx:29](../../web/src/components/admin/agent-form.tsx#L29)<br>[web/src/components/admin/agent-form.tsx:29](../../web/src/components/admin/agent-form.tsx#L29) | 1. AI 계정 사용 범위<br>2. credential 범위<br>3. 계정 공유 범위 | **AI 계정 사용 범위**. credential이 AI 서비스 인증 정보인지, 사용자에게 보일 말을 정해야 합니다. |
| `실행 나무를 읽지 못했다`<br>`나무로 보기 →`<br>`실행 나무를 읽는 중` | [web/src/components/chat/activity/activity-block.tsx:38](../../web/src/components/chat/activity/activity-block.tsx#L38)<br>[web/src/components/chat/activity/activity-block.tsx:92](../../web/src/components/chat/activity/activity-block.tsx#L92)<br>[web/src/components/chat/activity/activity-panel.tsx:32](../../web/src/components/chat/activity/activity-panel.tsx#L32)<br>[web/src/components/chat/activity/activity-panel.tsx:58](../../web/src/components/chat/activity/activity-panel.tsx#L58)<br>[web/src/components/chat/activity/activity-panel.tsx:63](../../web/src/components/chat/activity/activity-panel.tsx#L63) | 1. 작업 과정 자세히 보기<br>2. 실행 나무 보기<br>3. 실행 기록 자세히 보기 | **작업 과정 자세히 보기**. 대화 화면과 실행 상세 화면의 같은 구조를 부르는 이름을 정해야 합니다. |
| `Memory를 저장하지 못했습니다.`<br>`새 Memory`<br>`Memory를 고치지 못했습니다.`<br>`Memory를 지우지 못했습니다.`<br>`항상 답에 함께 넣음`<br>`필요할 때 제목만 넣음`<br>`받아들인 Memory만 다음 대화에 사용합니다.`<br>`아직 그룹 공용 Memory가 없습니다.`<br>`아직 개인 Memory가 없습니다.` | [web/src/components/memory/memory-form.tsx:23](../../web/src/components/memory/memory-form.tsx#L23)<br>[web/src/components/memory/memory-form.tsx:26](../../web/src/components/memory/memory-form.tsx#L26)<br>[web/src/components/memory/memory-form.tsx:31](../../web/src/components/memory/memory-form.tsx#L31)<br>[web/src/components/memory/memory-item.tsx:22](../../web/src/components/memory/memory-item.tsx#L22)<br>[web/src/components/memory/memory-item.tsx:24](../../web/src/components/memory/memory-item.tsx#L24)<br>[web/src/components/memory/memory-item.tsx:33](../../web/src/components/memory/memory-item.tsx#L33)<br>[web/src/components/memory/memory-item.tsx:35](../../web/src/components/memory/memory-item.tsx#L35)<br>[web/src/components/memory/memory-item.tsx:44](../../web/src/components/memory/memory-item.tsx#L44)<br>[web/src/components/memory/memory-item.tsx:44](../../web/src/components/memory/memory-item.tsx#L44)<br>[web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18)<br>[web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18)<br>[web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18) | 1. 기억<br>2. Memory | **기억**. 메뉴는 기억인데 본문과 오류 안내는 Memory이므로 이름을 통일해야 합니다. 해요체는 선택 결과에 맞춰 적용합니다. |
| `받을지 정할 것`<br>`우리 그룹이 함께 아는 것`<br>`나에 대해 아는 것` | [web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18)<br>[web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18)<br>[web/src/components/memory/memory-list.tsx:18](../../web/src/components/memory/memory-list.tsx#L18) | 1. 검토할 기억/그룹이 함께 아는 것/나에 대해 아는 것<br>2. 받을지 정할 것/우리 그룹이 함께 아는 것/나에 대해 아는 것 | **검토할 기억/그룹이 함께 아는 것/나에 대해 아는 것**. 기억의 제안·공유·개인 분류를 사용자에게 보여 주는 이름을 정해야 합니다. |
| `설정 지문`<br>`무엇이 달라졌나`<br>`지문` | [web/src/components/usage/breakdown-section.tsx:12](../../web/src/components/usage/breakdown-section.tsx#L12)<br>[web/src/components/usage/fingerprint-section.tsx:38](../../web/src/components/usage/fingerprint-section.tsx#L38)<br>[web/src/components/usage/fingerprint-section.tsx:39](../../web/src/components/usage/fingerprint-section.tsx#L39)<br>[web/src/components/usage/fingerprint-section.tsx:45](../../web/src/components/usage/fingerprint-section.tsx#L45) | 1. 설정별 사용량/설정 차이/설정 구분값<br>2. 설정 지문/무엇이 달라졌나/지문 | **설정별 사용량/설정 차이/설정 구분값**. 설정 지문이 무엇을 비교하는 화면인지 이름을 정해야 합니다. |

## 뜻이 틀리거나 오해를 부르는 안내

| 지금 | 제안 | 까닭 |
| --- | --- | --- |
| `가족이 함께 쓰는 개인 AI 비서`<br>[web/src/app/layout.tsx:17](../../web/src/app/layout.tsx#L17) | 함께 쓰는 AI 비서 | 제품은 가족 이외의 그룹도 다룰 수 있는데 소개 문구가 가족으로 한정해요. |
| `profile 이름은 소문자와 숫자와 붙임표만 쓴다. 더하면 Hermes profile 과 key 가 함께 만들어진다.`<br>[web/src/components/admin/person-form.tsx:34](../../web/src/components/admin/person-form.tsx#L34) | profile 이름에는 소문자, 숫자, 붙임표만 쓸 수 있어요. 사용자를 추가하면 기본 profile과 key가 만들어져요. | 사용자 추가 시 사용자와 에이전트가 언제 생성되는지는 관리 화면 설명과 대조가 필요해요. 기본 profile 생성만 확정해서 씁니다. |
| `저장하면 앞의 본문이 사라지고 되돌릴 수 없습니다. 이 성격으로 앞으로의 대화가 답합니다.`<br>[web/src/components/agent/persona-confirm.tsx:27](../../web/src/components/agent/persona-confirm.tsx#L27) | 저장하면 기존 본문을 되돌릴 수 없어요. 이후 시작하는 대화에는 새 성격이 적용돼요. | 새 성격이 적용되는 주체가 대화라고 쓰여 있어 인과가 어색해요. 이후 대화에 성격이 적용된다고 씁니다. |
| `응답이 제한 시간 안에 끝나지 않았다. 잠시 뒤에 다시 보낸다.`<br>`지금 붐빈다. 잠시 뒤에 다시 보낸다.`<br>[web/src/components/error-message.ts:14](../../web/src/components/error-message.ts#L14)<br>[web/src/components/error-message.ts:16](../../web/src/components/error-message.ts#L16) | 응답이 제한 시간 안에 끝나지 않았어요. 잠시 뒤 다시 보내 주세요.<br>지금 요청이 많아요. 잠시 뒤 다시 보내 주세요. | 다시 보내는 주체가 생략되어 비서가 자동 재시도한다고 읽혀요. |
| `위쪽이 잘려 여기가 뿌리가 아닐 수 있다`<br>[web/src/components/execution/execution-tree.tsx:89](../../web/src/components/execution/execution-tree.tsx#L89) | 위쪽 기록이 없어 이곳이 첫 실행이 아닐 수 있어요 | 위쪽 기록의 누락을 나무의 뿌리라는 내부 구조로 설명해요. |
| `실제 청구액`<br>`실제로 나간 돈`<br>``${monthly.month} 실제로 청구되는 금액``<br>`두 금액 모두 공개 가격표로 계산한 것이고 청구서를 읽은 것이 아니다.`<br>[web/src/components/usage/breakdown-table.tsx:67](../../web/src/components/usage/breakdown-table.tsx#L67)<br>[web/src/components/usage/breakdown-table.tsx:108](../../web/src/components/usage/breakdown-table.tsx#L108)<br>[web/src/components/usage/execution-card.tsx:65](../../web/src/components/usage/execution-card.tsx#L65)<br>[web/src/components/usage/execution-table.tsx:35](../../web/src/components/usage/execution-table.tsx#L35)<br>[web/src/components/usage/monthly-summary.tsx:23](../../web/src/components/usage/monthly-summary.tsx#L23)<br>[web/src/components/usage/monthly-summary.tsx:29](../../web/src/components/usage/monthly-summary.tsx#L29)<br>[web/src/components/usage/monthly-summary.tsx:51](../../web/src/components/usage/monthly-summary.tsx#L51) | 예상 추가 사용 요금<br>`${monthly.month}의 예상 추가 사용 요금`<br>두 금액은 공개 가격표를 이용한 계산값이에요. 실제 청구 금액과 다를 수 있어요. | 가격표로 추산한 금액을 실제로 나간 돈이나 실제 청구 금액으로 단정해요. |

## 바꿀 것

사용자가 정할 항목은 위 표에서 결정한 뒤 문구를 확정합니다. 아래 표에는 바로 제안할 수 있는 변경을 적었습니다.

### `web/src/app/admin/agents/agent-admin-panel.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/admin/agents/agent-admin-panel.tsx:191](../../web/src/app/admin/agents/agent-admin-panel.tsx#L191) | `공개 범위는 보안 설정이다. 그룹 공개로 바꾸면 그룹의 모든 사용자가 이 에이전트로 대화할 수 있다. 연결된 도구와 자료도 함께 쓸 수 있는지 확인해야 한다.` | 공개 범위는 보안 설정이에요. 그룹에 공개하면 그룹의 모든 사용자가 이 에이전트로 대화할 수 있어요. 연결된 도구와 자료를 함께 써도 되는지 확인해 주세요. | 해요체와 사용자 행동을 분명히 합니다. |

### `web/src/app/agents/page.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/agents/page.tsx:21](../../web/src/app/agents/page.tsx#L21) | `쓸 수 있는 에이전트가 없습니다` | 사용할 수 있는 에이전트가 없어요 | 빈 상태를 해요체로 바꿉니다. |
| [web/src/app/agents/page.tsx:22](../../web/src/app/agents/page.tsx#L22) | `관리자가 에이전트를 연결하면 여기에 나타납니다` | 관리자가 에이전트를 연결하면 여기에 표시돼요 | 해요체로 바꾸고 화면 동작을 쉽게 씁니다. |

### `web/src/app/api/admin/people/[id]/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/admin/people/[id]/route.ts:10](../../web/src/app/api/admin/people/[id]/route.ts#L10) | `사람 번호 형식이 올바르지 않습니다.` | 사용자 번호 형식이 올바르지 않아요. | 사람 대신 사용자라고 부르고 해요체로 바꿉니다. |

### `web/src/app/api/agents/[code]/persona/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/agents/[code]/persona/route.ts:26](../../web/src/app/api/agents/[code]/persona/route.ts#L26)<br>[web/src/app/api/agents/[code]/starters/route.ts:26](../../web/src/app/api/agents/[code]/starters/route.ts#L26) | `요청 본문이 올바르지 않습니다.` | 요청 내용이 올바르지 않아요. | 요청 본문은 내부 용어이므로 쉬운 말로 바꿉니다. |

### `web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts:11](../../web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts#L11) | `대화 주소나 첨부 번호가 올바르지 않습니다.` | 대화 주소나 첨부 파일 번호가 올바르지 않아요. | 첨부 번호의 대상을 밝히고 해요체로 바꿉니다. |
| [web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts:33](../../web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts#L33) | `사진을 읽지 못했습니다.` | 사진을 불러오지 못했어요. | 해요체로 바꿉니다. |

### `web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts:14](../../web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts#L14)<br>[web/src/app/api/chat/conversations/[conversationId]/messages/route.ts:13](../../web/src/app/api/chat/conversations/[conversationId]/messages/route.ts#L13)<br>[web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts:14](../../web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts#L14)<br>[web/src/app/api/chat/conversations/[conversationId]/route.ts:13](../../web/src/app/api/chat/conversations/[conversationId]/route.ts#L13)<br>[web/src/app/api/chat/conversations/[conversationId]/running/route.ts:14](../../web/src/app/api/chat/conversations/[conversationId]/running/route.ts#L14)<br>[web/src/app/api/chat/route.ts:22](../../web/src/app/api/chat/route.ts#L22)<br>[web/src/app/api/chat/stream/route.ts:20](../../web/src/app/api/chat/stream/route.ts#L20) | `대화 주소가 올바르지 않습니다.` | 대화 주소가 올바르지 않아요. | 해요체로 바꿉니다. |
| [web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts:37](../../web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts#L37) | `사진이 너무 큽니다.` | 사진이 너무 커요. | 해요체로 바꿉니다. |
| [web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts:46](../../web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts#L46)<br>[web/src/components/chat-panel.tsx:824](../../web/src/components/chat-panel.tsx#L824)<br>[web/src/components/chat-panel.tsx:894](../../web/src/components/chat-panel.tsx#L894)<br>[web/src/lib/control-plane.ts:144](../../web/src/lib/control-plane.ts#L144)<br>[web/src/lib/control-plane.ts:169](../../web/src/lib/control-plane.ts#L169) | `요청을 처리하지 못했습니다.`<br>`요청을 처리하지 못했다.` | 요청을 처리하지 못했어요. | 해요체로 바꿉니다. |

### `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts:19](../../web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts#L19) | `대화 주소나 파일 경로가 올바르지 않습니다.` | 대화 주소나 파일 경로가 올바르지 않아요. | 해요체로 바꿉니다. |
| [web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts:42](../../web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts#L42) | `파일을 읽지 못했습니다.` | 파일을 불러오지 못했어요. | 해요체로 바꿉니다. |

### `web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts:34](../../web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts#L34)<br>[web/src/app/api/chat/stream/route.ts:45](../../web/src/app/api/chat/stream/route.ts#L45) | `스트림을 열지 못했습니다.` | 응답 연결을 열지 못했어요. | 사용자에게 스트림 대신 연결이라고 말합니다. |

### `web/src/app/api/chat/executions/[id]/stop/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/chat/executions/[id]/stop/route.ts:7](../../web/src/app/api/chat/executions/[id]/stop/route.ts#L7)<br>[web/src/app/api/usage/executions/[id]/tree/route.ts:6](../../web/src/app/api/usage/executions/[id]/tree/route.ts#L6) | `실행 번호가 올바르지 않습니다.`<br>`실행 번호가 올바르지 않다.` | 실행 번호가 올바르지 않아요. | 해요체로 바꿉니다. |

### `web/src/app/api/memories/[id]/accept/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/memories/[id]/accept/route.ts:6](../../web/src/app/api/memories/[id]/accept/route.ts#L6)<br>[web/src/app/api/memories/[id]/reject/route.ts:6](../../web/src/app/api/memories/[id]/reject/route.ts#L6)<br>[web/src/app/api/memories/[id]/route.ts:10](../../web/src/app/api/memories/[id]/route.ts#L10) | `Memory 번호가 올바르지 않습니다.` | 기억 번호가 올바르지 않아요. | Memory 표기를 메뉴의 기억과 맞춥니다. 기억이라는 이름을 확정하면 적용합니다. |

### `web/src/app/layout.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/layout.tsx:17](../../web/src/app/layout.tsx#L17) | `가족이 함께 쓰는 개인 AI 비서` | 함께 쓰는 AI 비서 | 가족 외 그룹도 쓸 수 있어 범위를 좁히는 소개를 고칩니다. |

### `web/src/app/signin/page.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/signin/page.tsx:18](../../web/src/app/signin/page.tsx#L18) | `사용자로 등록된 Google 계정으로 로그인한다.` | 등록된 Google 계정으로 로그인해 주세요. | 사용자 등록이라는 내부 표현을 줄이고 행동을 분명히 합니다. |

### `web/src/app/usage/page.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/usage/page.tsx:33](../../web/src/app/usage/page.tsx#L33) | `구독제로 도는 실행은 실제로 추가 청구되지 않는다. 종량 모델로 옮기면 얼마가 나갈지도 함께 보여, 모델을 옮길지 판단할 수 있게 한다.` | 구독 경로로 실행한 항목은 추가 사용 요금이 없어요. 같은 사용량을 API 가격으로 계산한 금액도 보여 드려요. | 해요체로 바꾸고 계산 금액과 청구액을 구분합니다. |

### `web/src/components/admin/agent-card.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/admin/agent-card.tsx:79](../../web/src/components/admin/agent-card.tsx#L79) | `저장하기 전에 이 주소가 응답하는지 확인한다.` | 저장하기 전에 이 주소에 연결되는지 확인해 주세요. | 해요체로 바꾸고 확인할 행동을 분명히 합니다. |
| [web/src/components/admin/agent-card.tsx:124](../../web/src/components/admin/agent-card.tsx#L124) | `모델 다시 읽기` | 모델 목록 다시 읽기 | 무엇을 다시 읽는지 밝힙니다. |
| [web/src/components/admin/agent-card.tsx:65](../../web/src/components/admin/agent-card.tsx#L65) | `모델을 마지막으로 읽은 시각` | 모델 목록을 마지막으로 읽은 시각 | 읽은 대상이 모델 목록임을 밝힙니다. |

### `web/src/components/admin/agent-list.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/admin/agent-list.tsx:19](../../web/src/components/admin/agent-list.tsx#L19)<br>[web/src/components/chat-panel.tsx:980](../../web/src/components/chat-panel.tsx#L980) | `등록된 에이전트가 없다`<br>`등록된 에이전트 없음` | 등록된 에이전트가 없어요 | 해요체로 바꿉니다. |
| [web/src/components/admin/agent-list.tsx:19](../../web/src/components/admin/agent-list.tsx#L19) | `위 양식에서 첫 에이전트를 등록한다.` | 위 양식에서 첫 에이전트를 등록해 주세요. | 사용자에게 요청하는 문장으로 바꿉니다. |

### `web/src/components/admin/person-form.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/admin/person-form.tsx:18](../../web/src/components/admin/person-form.tsx#L18) | `사람 더하기` | 사용자 추가 | 사람을 가리키는 관리 용어를 사용자로 통일합니다. |
| [web/src/components/admin/person-form.tsx:34](../../web/src/components/admin/person-form.tsx#L34) | `profile 이름은 소문자와 숫자와 붙임표만 쓴다. 더하면 Hermes profile 과 key 가 함께 만들어진다.` | profile 이름에는 소문자, 숫자, 붙임표만 쓸 수 있어요. 사용자를 추가하면 기본 profile과 key가 만들어져요. | 해요체로 바꾸고 생성 대상을 정확히 밝힙니다. profile 노출 여부를 결정한 뒤 최종 문구를 확정합니다. |
| [web/src/components/admin/person-form.tsx:36](../../web/src/components/admin/person-form.tsx#L36) | `더하는 중` | 추가하는 중 | 사용자 추가 동작을 같은 말로 통일합니다. |
| [web/src/components/admin/person-form.tsx:37](../../web/src/components/admin/person-form.tsx#L37) | `더하기` | 추가 | 사용자 추가 동작을 같은 말로 통일합니다. |

### `web/src/components/admin/person-list.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/admin/person-list.tsx:18](../../web/src/components/admin/person-list.tsx#L18) | `아직 아무도 없습니다` | 아직 등록된 사용자가 없어요 | 사람 대신 사용자라고 부르고 해요체로 바꿉니다. |
| [web/src/components/admin/person-list.tsx:18](../../web/src/components/admin/person-list.tsx#L18) | `위 양식에서 첫 사람을 더한다.` | 위 양식에서 첫 사용자를 추가해 주세요. | 사람 대신 사용자라고 부르고 해요체로 바꿉니다. |
| [web/src/components/admin/person-list.tsx:22](../../web/src/components/admin/person-list.tsx#L22) | `더해진 사람` | 등록된 사용자 | 사람 대신 사용자라고 부릅니다. |
| [web/src/components/admin/person-list.tsx:39](../../web/src/components/admin/person-list.tsx#L39) | `들어온 적 있음` | 로그인한 적 있음 | 상태의 대상을 밝힙니다. |
| [web/src/components/admin/person-list.tsx:39](../../web/src/components/admin/person-list.tsx#L39) | `아직 없음` | 로그인한 적 없음 | 상태의 대상을 밝힙니다. |

### `web/src/app/admin/people/people-admin-panel.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/admin/people/people-admin-panel.tsx:79](../../web/src/app/admin/people/people-admin-panel.tsx#L79)<br>[web/src/components/shell/main-nav.tsx:32](../../web/src/components/shell/main-nav.tsx#L32) | `사람 관리` | 사용자 관리 | 사람 대신 사용자라고 부릅니다. |

### `web/src/components/admin/visibility-confirm.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/admin/visibility-confirm.tsx:31](../../web/src/components/admin/visibility-confirm.tsx#L31) | `모든 사용자가 이 에이전트를 골라 대화할 수 있게 된다. 연결된 도구와 자료를 함께 쓸 수 있는지 확인한 뒤 공개한다.` | 그룹의 모든 사용자가 이 에이전트로 대화할 수 있어요. 연결된 도구와 자료를 함께 써도 되는지 확인한 뒤 공개해 주세요. | 해요체와 사용자 행동을 분명히 합니다. |

### `web/src/components/agent/persona-confirm.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/agent/persona-confirm.tsx:27](../../web/src/components/agent/persona-confirm.tsx#L27) | `저장하면 앞의 본문이 사라지고 되돌릴 수 없습니다. 이 성격으로 앞으로의 대화가 답합니다.` | 저장하면 기존 본문을 되돌릴 수 없어요. 이후 시작하는 대화에는 새 성격이 적용돼요. | 무엇이 바뀌는지와 적용 시점을 명확히 합니다. |
| [web/src/components/agent/persona-confirm.tsx:36](../../web/src/components/agent/persona-confirm.tsx#L36) | `저장한다` | 저장 | 버튼을 명사형으로 통일합니다. |

### `web/src/components/agent/persona-editor.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/agent/persona-editor.tsx:89](../../web/src/components/agent/persona-editor.tsx#L89) | `이 에이전트가 대화마다 지키는 성격입니다. 저장하면 곧바로 다음 대화부터 반영됩니다.` | 이 에이전트가 대화마다 따르는 성격이에요. 저장한 내용은 다음 대화부터 반영돼요. | 해요체와 적용 시점을 통일합니다. |
| [web/src/components/agent/persona-editor.tsx:111](../../web/src/components/agent/persona-editor.tsx#L111) | `쓰던 글은 위 편집창에 그대로 있습니다. 둘을 견주어 남길 내용을 정한 뒤 다시 저장해 주세요.` | 작성하던 글은 위 편집창에 그대로 있어요. 현재 저장된 글과 비교해 남길 내용을 정한 뒤 다시 저장해 주세요. | 해요체로 바꾸고 비교 대상을 밝힙니다. |
| [web/src/components/agent/persona-editor.tsx:80](../../web/src/components/agent/persona-editor.tsx#L80) | `저장하지 못했습니다.` | 저장하지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/agent/persona-editor.tsx:91](../../web/src/components/agent/persona-editor.tsx#L91) | `아직 성격을 쓰지 않았습니다.` | 아직 성격을 쓰지 않았어요. | 해요체로 바꿉니다. |
| [web/src/components/agent/persona-editor.tsx:116](../../web/src/components/agent/persona-editor.tsx#L116) | `저장되었습니다.` | 저장했어요. | 해요체로 바꿉니다. |

### `web/src/components/agent/starter-editor.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/agent/starter-editor.tsx:71](../../web/src/components/agent/starter-editor.tsx#L71) | `저장하지 못했다. 잠시 뒤 다시 시도해 주세요.` | 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요. | 한 문장 안의 말투를 통일합니다. |
| [web/src/components/agent/starter-editor.tsx:79](../../web/src/components/agent/starter-editor.tsx#L79) | `새 대화에서 이 에이전트를 고르면 보인다.` | 새 대화에서 이 에이전트를 고르면 보여요. | 해요체로 바꿉니다. |
| [web/src/components/agent/starter-editor.tsx:106](../../web/src/components/agent/starter-editor.tsx#L106) | `소개와 추천 질문이 저장되었습니다.` | 소개와 추천 질문을 저장했어요. | 해요체로 바꿉니다. |

### `web/src/components/chat/activity/activity-block.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/activity/activity-block.tsx:76](../../web/src/components/chat/activity/activity-block.tsx#L76) | `오래 걸릴 수 있다. 이 화면을 떠나도 된다. 실행은 계속 돌고, 나중에 다시 열면 저장된 답이 보인다.` | 오래 걸릴 수 있어요. 이 화면을 떠나도 실행은 계속돼요. 나중에 대화를 다시 열면 저장된 답을 볼 수 있어요. | 해요체로 바꾸고 다시 열 대상을 밝힙니다. |
| [web/src/components/chat/activity/activity-block.tsx:83](../../web/src/components/chat/activity/activity-block.tsx#L83) | `작업 과정을 읽지 못했다` | 작업 과정을 읽지 못했어요 | 해요체로 바꿉니다. |
| [web/src/components/chat/activity/activity-block.tsx:88](../../web/src/components/chat/activity/activity-block.tsx#L88) | `작업 과정을 읽는 중` | 작업 과정을 읽고 있어요 | 해요체로 바꿉니다. |

### `web/src/components/chat/activity/activity-state.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/activity/activity-state.ts:105](../../web/src/components/chat/activity/activity-state.ts#L105) | ``여기부터 ${event.text ?? ""} 로 돈다`` | `여기부터 ${event.text ?? ""}로 실행해요` | 조사 앞 공백을 없애고 무엇을 실행하는지 읽히게 합니다. |

### `web/src/components/chat/activity/activity-timeline.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/activity/activity-timeline.tsx:17](../../web/src/components/chat/activity/activity-timeline.tsx#L17)<br>[web/src/components/execution/execution-detail.tsx:25](../../web/src/components/execution/execution-detail.tsx#L25)<br>[web/src/components/usage/execution-list.tsx:64](../../web/src/components/usage/execution-list.tsx#L64) | `도는 중` | 실행 중 | 상태 이름을 한 단어로 통일합니다. |
| [web/src/components/chat/activity/activity-timeline.tsx:23](../../web/src/components/chat/activity/activity-timeline.tsx#L23) | `작업 과정의 사건` | 작업 과정 | 사건이라는 내부 용어를 빼고 접근성 이름을 간결하게 합니다. |
| [web/src/components/chat/activity/activity-timeline.tsx:47](../../web/src/components/chat/activity/activity-timeline.tsx#L47) | `결과 -` | 결과를 받지 못함 | 기호만으로 결과 부재를 표현하지 않습니다. |

### `web/src/components/chat/agent-mention.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/agent-mention.tsx:54](../../web/src/components/chat/agent-mention.tsx#L54) | `맞는 에이전트가 없다` | 맞는 에이전트가 없어요 | 해요체로 바꿉니다. |

### `web/src/components/chat/ask-card.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/ask-card.tsx:95](../../web/src/components/chat/ask-card.tsx#L95) | `여럿 고를 수 있다` | 여러 개를 고를 수 있어요 | 해요체로 바꿉니다. |

### `web/src/components/chat/composer.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/composer.tsx:70](../../web/src/components/chat/composer.tsx#L70) | `캔버스를 만들지 못했다.` | 캔버스를 만들지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/chat/composer.tsx:77](../../web/src/components/chat/composer.tsx#L77) | `미리보기를 만들지 못했다.` | 미리보기를 만들지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/chat/composer.tsx:215](../../web/src/components/chat/composer.tsx#L215)<br>[web/src/components/chat/composer.tsx:223](../../web/src/components/chat/composer.tsx#L223) | `대화를 시작하지 못했다. 잠시 뒤 다시 시도해 주세요.` | 대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요. | 한 문구 안의 말투를 통일합니다. |
| [web/src/components/chat/composer.tsx:315](../../web/src/components/chat/composer.tsx#L315) | ``이미지 파일만 올릴 수 있다. ${rejectedFormatCount}장은 올리지 않았다.`` | `이미지 파일만 올릴 수 있어요. ${rejectedFormatCount}장은 올리지 못했어요.` | 해요체로 바꾸고 실패한 결과를 설명합니다. |
| [web/src/components/chat/composer.tsx:318](../../web/src/components/chat/composer.tsx#L318) | ``한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있다. ${overflowCount}장은 올리지 않았다.`` | `한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있어요. ${overflowCount}장은 올리지 못했어요.` | 해요체로 바꾸고 실패한 결과를 설명합니다. |
| [web/src/components/chat/composer.tsx:321](../../web/src/components/chat/composer.tsx#L321) | ``한 장은 10MB 까지 올릴 수 있다. ${oversize.length}장은 올리지 않았다.`` | `사진 한 장은 10MB까지 올릴 수 있어요. ${oversize.length}장은 올리지 못했어요.` | 해요체로 바꾸고 단위 앞 공백을 바로잡습니다. |
| [web/src/components/chat/composer.tsx:507](../../web/src/components/chat/composer.tsx#L507) | `@ 로 에이전트를 부른다` | @로 에이전트를 불러요 | 해요체로 바꿉니다. |
| [web/src/components/chat/composer.tsx:507](../../web/src/components/chat/composer.tsx#L507)<br>[web/src/components/chat/start-screen.tsx:31](../../web/src/components/chat/start-screen.tsx#L31)<br>[web/src/components/chat/start-screen.tsx:31](../../web/src/components/chat/start-screen.tsx#L31) | `무엇을 도와줄까요`<br>``${displayName}님, 무엇을 도와줄까요`` | 무엇을 도와드릴까요? | 비서가 사용자에게 묻는 말에 높임을 쓰고 물음표를 붙입니다. 이름을 부르는 문구에는 이름을 유지합니다. |
| [web/src/components/chat/composer.tsx:286](../../web/src/components/chat/composer.tsx#L286) | `올리지 못했습니다.` | 사진을 올리지 못했어요. | 어떤 항목을 올리지 못했는지 밝히고 해요체로 바꿉니다. |
| [web/src/components/chat/composer.tsx:294](../../web/src/components/chat/composer.tsx#L294) | `올리지 못했습니다. 다시 시도해 주세요.` | 사진을 올리지 못했어요. 다시 시도해 주세요. | 같은 문구 안의 말투를 통일합니다. |

### `web/src/components/chat/message-actions.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/message-actions.tsx:16](../../web/src/components/chat/message-actions.tsx#L16) | `다시 생성` | 답 다시 만들기 | 다시 생성이라는 시스템 용어를 쉬운 말로 바꿉니다. |

### `web/src/components/chat/message-list.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/message-list.tsx:155](../../web/src/components/chat/message-list.tsx#L155) | `답을 받지 못했다` | 답을 받지 못했어요 | 해요체로 바꿉니다. |
| [web/src/components/chat/message-list.tsx:124](../../web/src/components/chat/message-list.tsx#L124) | `무엇이든 물어보세요.` | 무엇이든 물어봐 주세요. | 비서가 사용자에게 하는 말에 높임을 씁니다. |

### `web/src/components/chat/start-screen.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/start-screen.tsx:43](../../web/src/components/chat/start-screen.tsx#L43) | `쓸 수 있는 에이전트가 없다. 관리자에게 등록을 요청한다.` | 사용할 수 있는 에이전트가 없어요. 관리자에게 등록을 요청해 주세요. | 해요체와 사용자 행동을 분명히 합니다. |

### `web/src/components/chat/version-switcher.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/version-switcher.tsx:16](../../web/src/components/chat/version-switcher.tsx#L16) | `이전 판` | 이전 답 | 판이라는 새 번역어 대신 실제 대상을 밝힙니다. |
| [web/src/components/chat/version-switcher.tsx:20](../../web/src/components/chat/version-switcher.tsx#L20) | `다음 판` | 다음 답 | 판이라는 새 번역어 대신 실제 대상을 밝힙니다. |

### `web/src/components/chat/waiting-indicator.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/waiting-indicator.tsx:14](../../web/src/components/chat/waiting-indicator.tsx#L14) | `비서가 답을 준비하고 있다.` | 비서가 답을 준비하고 있어요. | 해요체로 바꿉니다. |

### `web/src/components/chat-panel.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat-panel.tsx:235](../../web/src/components/chat-panel.tsx#L235)<br>[web/src/components/chat-panel.tsx:280](../../web/src/components/chat-panel.tsx#L280) | `대화 이력을 읽지 못했다.` | 대화 이력을 읽지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/chat-panel.tsx:705](../../web/src/components/chat-panel.tsx#L705) | ``${message} 대화 이력을 다시 읽지 못했다. 아래에서 다시 시도하거나 대화를 새로고침해 주세요.`` | `${message} 대화 이력을 다시 읽지 못했어요. 아래에서 다시 시도하거나 대화를 새로고침해 주세요.` | 한 문구 안의 말투를 통일합니다. |
| [web/src/components/chat-panel.tsx:714](../../web/src/components/chat-panel.tsx#L714)<br>[web/src/components/chat-panel.tsx:916](../../web/src/components/chat-panel.tsx#L916) | `응답 연결이 끊겼다.` | 응답 연결이 끊겼어요. | 해요체로 바꿉니다. |
| [web/src/components/chat-panel.tsx:843](../../web/src/components/chat-panel.tsx#L843) | `요청을 보내지 못했다.` | 요청을 보내지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/chat-panel.tsx:922](../../web/src/components/chat-panel.tsx#L922) | `다시 생성하지 못했다.` | 답을 다시 만들지 못했어요. | 동작을 쉬운 말로 바꾸고 해요체로 끝냅니다. |
| [web/src/components/chat-panel.tsx:946](../../web/src/components/chat-panel.tsx#L946) | `중지 요청을 보내지 못했다.` | 중지 요청을 보내지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/chat-panel.tsx:959](../../web/src/components/chat-panel.tsx#L959) | `대화를 찾을 수 없다` | 대화를 찾을 수 없어요 | 해요체로 바꿉니다. |
| [web/src/components/chat-panel.tsx:1025](../../web/src/components/chat-panel.tsx#L1025) | `응답 연결이 끊겨 답을 기다리는 중이다. 끝나면 답이 나타난다.` | 응답 연결이 끊겨 답을 기다리고 있어요. 답이 완성되면 여기에 나타나요. | 해요체로 바꾸고 나타나는 위치를 밝힙니다. |
| [web/src/components/chat-panel.tsx:1026](../../web/src/components/chat-panel.tsx#L1026) | `다른 창에서 답하는 중이다. 끝나면 이 창에도 답이 나타난다.` | 다른 창에서 답을 만들고 있어요. 완성되면 이 창에도 나타나요. | 해요체로 바꾸고 비서의 동작을 밝힙니다. |

### `web/src/components/error-message.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/error-message.ts:3](../../web/src/components/error-message.ts#L3) | `없는 에이전트이거나 이 계정에서 쓸 수 없는 에이전트다.` | 에이전트가 없거나 이 계정에서 사용할 수 없어요. | 해요체로 바꾸고 사용자가 이해하는 말로 씁니다. |
| [web/src/components/error-message.ts:4](../../web/src/components/error-message.ts#L4) | `이 에이전트는 지금 쓰지 않도록 되어 있다.` | 이 에이전트는 지금 사용할 수 없어요. | 해요체로 바꿉니다. |
| [web/src/components/error-message.ts:7](../../web/src/components/error-message.ts#L7) | `연결된 AI 계정이 사용 중지 상태다.` | 연결된 AI 계정이 사용 중지 상태예요. | 해요체로 바꿉니다. |
| [web/src/components/error-message.ts:11](../../web/src/components/error-message.ts#L11) | `그 이메일은 이미 목록에 있다.` | 이 이메일은 이미 등록되어 있어요. | 해요체로 바꾸고 등록 상태를 분명히 합니다. |
| [web/src/components/error-message.ts:13](../../web/src/components/error-message.ts#L13) | `목록에 없는 사람이다. 화면을 새로 고쳐 확인한다.` | 등록되지 않은 사용자예요. 화면을 새로고침해 확인해 주세요. | 사용자로 통일하고 사용자 행동을 분명히 합니다. |
| [web/src/components/error-message.ts:14](../../web/src/components/error-message.ts#L14) | `응답이 제한 시간 안에 끝나지 않았다. 잠시 뒤에 다시 보낸다.` | 응답이 제한 시간 안에 끝나지 않았어요. 잠시 뒤 다시 보내 주세요. | 사용자가 다시 보내야 한다는 뜻을 분명히 합니다. |
| [web/src/components/error-message.ts:16](../../web/src/components/error-message.ts#L16) | `지금 붐빈다. 잠시 뒤에 다시 보낸다.` | 지금 요청이 많아요. 잠시 뒤 다시 보내 주세요. | 비유를 풀고 사용자가 다시 보내야 한다는 뜻을 분명히 합니다. |
| [web/src/components/error-message.ts:17](../../web/src/components/error-message.ts#L17) | `실행이 끝나지 못했다. 사용량 화면에서 기록을 확인할 수 있다.` | 실행을 마치지 못했어요. 사용량 화면에서 기록을 확인해 주세요. | 해요체와 사용자 행동을 분명히 합니다. |
| [web/src/components/error-message.ts:18](../../web/src/components/error-message.ts#L18) | `이미 끝난 답이다.` | 이미 끝난 답이에요. | 해요체로 바꿉니다. |
| [web/src/components/error-message.ts:19](../../web/src/components/error-message.ts#L19) | `응답 연결이 끊겼다. 실행은 계속될 수 있으니 잠시 뒤 대화 이력을 다시 확인한다.` | 응답 연결이 끊겼어요. 실행은 계속될 수 있으니 잠시 뒤 대화 이력을 다시 확인해 주세요. | 해요체와 사용자 행동을 분명히 합니다. |
| [web/src/components/error-message.ts:20](../../web/src/components/error-message.ts#L20) | `대화를 찾지 못했다. 대화 목록으로 돌아가 다시 골라 주세요.` | 대화를 찾지 못했어요. 대화 목록으로 돌아가 다시 골라 주세요. | 한 문구 안의 말투를 통일합니다. |
| [web/src/components/error-message.ts:21](../../web/src/components/error-message.ts#L21) | `그 사이 대화가 바뀌었다. 최신 대화를 다시 불러왔다.` | 그사이 대화가 바뀌었어요. 최신 대화를 다시 불러왔어요. | 해요체로 바꿉니다. |
| [web/src/components/error-message.ts:22](../../web/src/components/error-message.ts#L22) | `아직 답을 만드는 중이다. 끝난 뒤에 다시 누른다.` | 아직 답을 만들고 있어요. 답이 끝난 뒤 다시 눌러 주세요. | 해요체로 바꾸고 눌러야 할 때를 분명히 합니다. |
| [web/src/components/error-message.ts:23](../../web/src/components/error-message.ts#L23)<br>[web/src/lib/control-plane.ts:88](../../web/src/lib/control-plane.ts#L88) | `로그인이 필요하다.`<br>`로그인이 필요합니다.` | 로그인이 필요해요. | 해요체로 통일합니다. |
| [web/src/components/error-message.ts:24](../../web/src/components/error-message.ts#L24) | `그 사이 다른 사람이 이 성격을 고쳤다. 최신 본문을 다시 불러왔다.` | 그사이 다른 사용자가 이 성격을 고쳤어요. 최신 본문을 다시 불러왔어요. | 해요체와 사용자 용어로 통일합니다. |
| [web/src/components/error-message.ts:25](../../web/src/components/error-message.ts#L25)<br>[web/src/components/chat/message-bubble.tsx:139](../../web/src/components/chat/message-bubble.tsx#L139) | `보관 기간이 지나 볼 수 없습니다.`<br>`보관 기간이 지나 볼 수 없습니다` | 보관 기간이 지나 볼 수 없어요. | 해요체로 바꿉니다. |

### `web/src/components/execution/execution-detail.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/execution/execution-detail.tsx:67](../../web/src/components/execution/execution-detail.tsx#L67) | `불러오는 중이다.` | 불러오고 있어요. | 해요체로 바꿉니다. |
| [web/src/components/execution/execution-detail.tsx:75](../../web/src/components/execution/execution-detail.tsx#L75) | `실행 정보를 불러오지 못했다.` | 실행 정보를 불러오지 못했어요. | 해요체로 바꿉니다. |

### `web/src/components/execution/execution-node.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/execution/execution-node.tsx:113](../../web/src/components/execution/execution-node.tsx#L113) | `여기부터 보이지 않는다` | 이전 실행은 표시되지 않아요 | 기록이 보이지 않는 범위를 직접 말합니다. |

### `web/src/components/execution/execution-tree.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/execution/execution-tree.tsx:89](../../web/src/components/execution/execution-tree.tsx#L89) | `위쪽이 잘려 여기가 뿌리가 아닐 수 있다` | 위쪽 기록이 없어 이곳이 첫 실행이 아닐 수 있어요 | 뿌리라는 비유를 실제 상태로 바꿉니다. |
| [web/src/components/execution/execution-tree.tsx:97](../../web/src/components/execution/execution-tree.tsx#L97) | `기록된 사건이 없다` | 기록된 작업이 없어요 | 사건이라는 내부 용어를 쉬운 말로 바꿉니다. |

### `web/src/components/memory/memory-item.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/memory/memory-item.tsx:41](../../web/src/components/memory/memory-item.tsx#L41) | `길어서 실리지 않음` | 길어서 답에 포함되지 않음 | 무엇에 실리지 않는지 밝힙니다. |

### `web/src/components/memory/memory-proposal.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/memory/memory-proposal.tsx:17](../../web/src/components/memory/memory-proposal.tsx#L17)<br>[web/src/components/memory/memory-proposal.tsx:19](../../web/src/components/memory/memory-proposal.tsx#L19) | `제안을 처리하지 못했습니다.` | 제안을 처리하지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/memory/memory-proposal.tsx:22](../../web/src/components/memory/memory-proposal.tsx#L22) | `물리는 중` | 거절하는 중 | 기억 제안을 물린다는 뜻을 사용자에게 바로 알립니다. |
| [web/src/components/memory/memory-proposal.tsx:22](../../web/src/components/memory/memory-proposal.tsx#L22) | `물리기` | 거절 | 기억 제안을 물린다는 뜻을 사용자에게 바로 알립니다. |

### `web/src/components/shell/conversation-nav.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/shell/conversation-nav.tsx:59](../../web/src/components/shell/conversation-nav.tsx#L59) | `대화 이름을 바꾸지 못했다.` | 대화 이름을 바꾸지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/shell/conversation-nav.tsx:75](../../web/src/components/shell/conversation-nav.tsx#L75) | `대화를 지우지 못했다.` | 대화를 지우지 못했어요. | 해요체로 바꿉니다. |
| [web/src/components/shell/conversation-nav.tsx:93](../../web/src/components/shell/conversation-nav.tsx#L93) | `맞는 대화가 없다` | 맞는 대화가 없어요 | 해요체로 바꿉니다. |
| [web/src/components/shell/conversation-nav.tsx:93](../../web/src/components/shell/conversation-nav.tsx#L93) | `아직 대화가 없다.` | 아직 대화가 없어요. | 해요체로 바꿉니다. |
| [web/src/components/shell/conversation-nav.tsx:179](../../web/src/components/shell/conversation-nav.tsx#L179) | `를 목록에서 지운다. 사용량 기록은 남는다.` | 를 목록에서 지워요. 사용량 기록은 남아요. | 확인 창의 말투를 해요체로 바꿉니다. 앞에 붙는 대화 이름은 유지합니다. |
| [web/src/components/shell/conversation-nav.tsx:87](../../web/src/components/shell/conversation-nav.tsx#L87) | `대화 목록을 읽는 중` | 대화 목록을 읽고 있어요 | 해요체로 바꿉니다. |

### `web/src/components/shell/conversations-provider.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/shell/conversations-provider.tsx:31](../../web/src/components/shell/conversations-provider.tsx#L31)<br>[web/src/components/shell/conversations-provider.tsx:51](../../web/src/components/shell/conversations-provider.tsx#L51) | `대화 목록을 읽지 못했다.` | 대화 목록을 읽지 못했어요. | 해요체로 바꿉니다. |

### `web/src/components/ui/copy-button.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/ui/copy-button.tsx:20](../../web/src/components/ui/copy-button.tsx#L20) | `복사하지 못했다` | 복사하지 못했어요 | 해요체로 바꿉니다. |

### `web/src/components/ui/theme-toggle.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/ui/theme-toggle.tsx:41](../../web/src/components/ui/theme-toggle.tsx#L41) | ``밝기 모드: ${THEME_LABEL[theme]}. 다음은 ${THEME_LABEL[nextTheme]}`` | `밝기 모드: ${THEME_LABEL[theme]}. 다음 선택: ${THEME_LABEL[nextTheme]}` | 접근성 이름에서 다음 버튼을 누를 때의 결과를 분명히 합니다. |

### `web/src/components/usage/breakdown-section.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/usage/breakdown-section.tsx:29](../../web/src/components/usage/breakdown-section.tsx#L29)<br>[web/src/components/usage/breakdown-section.tsx:34](../../web/src/components/usage/breakdown-section.tsx#L34) | `축별 합계를 불러오지 못했다.` | 묶음별 합계를 불러오지 못했어요. | 축은 내부 용어이고 해요체가 아닙니다. |
| [web/src/components/usage/breakdown-section.tsx:41](../../web/src/components/usage/breakdown-section.tsx#L41)<br>[web/src/components/usage/breakdown-section.tsx:43](../../web/src/components/usage/breakdown-section.tsx#L43) | `어디에 썼나` | 사용량 내역 | 질문형 제목을 명사구로 바꿉니다. |

### `web/src/components/usage/breakdown-table.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/usage/breakdown-table.tsx:56](../../web/src/components/usage/breakdown-table.tsx#L56) | `기록이 없다` | 기록이 없어요 | 해요체로 바꿉니다. |
| [web/src/components/usage/breakdown-table.tsx:56](../../web/src/components/usage/breakdown-table.tsx#L56) | `그 달에는 끝난 실행이 없다.` | 그 달에는 완료된 실행이 없어요. | 해요체로 바꿉니다. |
| [web/src/components/usage/breakdown-table.tsx:67](../../web/src/components/usage/breakdown-table.tsx#L67)<br>[web/src/components/usage/breakdown-table.tsx:108](../../web/src/components/usage/breakdown-table.tsx#L108)<br>[web/src/components/usage/execution-card.tsx:65](../../web/src/components/usage/execution-card.tsx#L65)<br>[web/src/components/usage/execution-table.tsx:35](../../web/src/components/usage/execution-table.tsx#L35) | `실제 청구액` | 예상 추가 사용 요금 | 청구서를 읽지 않은 계산값을 청구액이라고 단정하지 않습니다. |

### `web/src/components/usage/execution-list.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/usage/execution-list.tsx:44](../../web/src/components/usage/execution-list.tsx#L44) | ``기억 ${omitted.toLocaleString("ko-KR")}개가 길어서 빠짐`` | `기억 ${omitted.toLocaleString("ko-KR")}개가 길어서 답에 포함되지 않았어요` | 무엇에서 빠졌는지 밝히고 해요체로 바꿉니다. |
| [web/src/components/usage/execution-list.tsx:65](../../web/src/components/usage/execution-list.tsx#L65) | `중간에 끊김` | 중간에 중단됨 | 실행이 중단된 상태를 정확히 부릅니다. |
| [web/src/components/usage/execution-list.tsx:66](../../web/src/components/usage/execution-list.tsx#L66) | `막혀서 다음 모델로 넘어감` | 다음 모델로 다시 시도함 | 사용량 기록에서 일어난 일을 짧게 씁니다. |
| [web/src/components/usage/execution-list.tsx:67](../../web/src/components/usage/execution-list.tsx#L67) | `쓸 수 있는 모델이 없음` | 사용할 수 있는 모델 없음 | 쉬운 표현으로 바꿉니다. |
| [web/src/components/usage/execution-list.tsx:69](../../web/src/components/usage/execution-list.tsx#L69) | `붐벼서 거절됨` | 요청이 많아 거절됨 | 비유를 풀어 씁니다. |
| [web/src/components/usage/execution-list.tsx:82](../../web/src/components/usage/execution-list.tsx#L82) | ``${execution.retryOfExecutionId}번 실행에서 이어짐`` | `${execution.retryOfExecutionId}번 실행에 이어서 시작함` | 실행 간 관계를 명확히 합니다. |
| [web/src/components/usage/execution-list.tsx:87](../../web/src/components/usage/execution-list.tsx#L87) | `아직 실행 기록이 없다` | 아직 실행 기록이 없어요 | 해요체로 바꿉니다. |
| [web/src/components/usage/execution-list.tsx:87](../../web/src/components/usage/execution-list.tsx#L87) | `에이전트와 대화하면 사용량이 여기에 쌓인다.` | 에이전트와 대화하면 사용량이 여기에 쌓여요. | 해요체로 바꿉니다. |

### `web/src/components/usage/monthly-summary.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/usage/monthly-summary.tsx:23](../../web/src/components/usage/monthly-summary.tsx#L23) | `실제로 나간 돈` | 예상 추가 사용 요금 | 청구서를 읽지 않은 값에 실제로 나간 돈이라고 쓰지 않습니다. |
| [web/src/components/usage/monthly-summary.tsx:29](../../web/src/components/usage/monthly-summary.tsx#L29) | ``${monthly.month} 실제로 청구되는 금액`` | `${monthly.month}의 예상 추가 사용 요금` | 청구액이 아니라 공개 가격표를 이용한 계산값으로 밝힙니다. |
| [web/src/components/usage/monthly-summary.tsx:32](../../web/src/components/usage/monthly-summary.tsx#L32) | `API 로 돌렸다면` | API 가격으로 계산한 금액 | API를 썼다는 가정과 계산값임을 밝힙니다. |
| [web/src/components/usage/monthly-summary.tsx:39](../../web/src/components/usage/monthly-summary.tsx#L39) | ``${monthly.subscriptionExecutions.toLocaleString("ko-KR")}건이 구독 경로다`` | `${monthly.subscriptionExecutions.toLocaleString("ko-KR")}건은 구독 경로로 실행했어요` | 해요체로 바꿉니다. |
| [web/src/components/usage/monthly-summary.tsx:51](../../web/src/components/usage/monthly-summary.tsx#L51) | `두 금액 모두 공개 가격표로 계산한 것이고 청구서를 읽은 것이 아니다.` | 두 금액은 공개 가격표를 이용한 계산값이에요. 실제 청구 금액과 다를 수 있어요. | 청구서를 읽지 않은 값을 실제 청구액이라고 단정하지 않습니다. |

### `web/src/app/api/admin/agents/[code]/models/route.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/app/api/admin/agents/[code]/models/route.ts:8](../../web/src/app/api/admin/agents/[code]/models/route.ts#L8)<br>[web/src/app/api/admin/agents/[code]/route.ts:9](../../web/src/app/api/admin/agents/[code]/route.ts#L9)<br>[web/src/app/api/admin/agents/[code]/sync-model/route.ts:9](../../web/src/app/api/admin/agents/[code]/sync-model/route.ts#L9)<br>[web/src/app/api/agents/[code]/persona/route.ts:8](../../web/src/app/api/agents/[code]/persona/route.ts#L8)<br>[web/src/app/api/agents/[code]/persona/route.ts:20](../../web/src/app/api/agents/[code]/persona/route.ts#L20)<br>[web/src/app/api/agents/[code]/starters/route.ts:8](../../web/src/app/api/agents/[code]/starters/route.ts#L8)<br>[web/src/app/api/agents/[code]/starters/route.ts:20](../../web/src/app/api/agents/[code]/starters/route.ts#L20) | `에이전트 코드 형식이 올바르지 않습니다.` | 에이전트 코드 형식이 올바르지 않아요. | 해요체로 바꿉니다. |

### `web/src/components/chat/message-bubble.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/chat/message-bubble.tsx:99](../../web/src/components/chat/message-bubble.tsx#L99) | `사진을 표시할 수 없습니다.` | 사진을 표시할 수 없어요. | 해요체로 바꿉니다. |
| [web/src/components/chat/message-bubble.tsx:231](../../web/src/components/chat/message-bubble.tsx#L231)<br>[web/src/components/chat/message-bubble.tsx:231](../../web/src/components/chat/message-bubble.tsx#L231) | `── 여기부터`<br>`로 돈다 ──` | 여기부터 {turn.switchedTo}로 실행해요 | 화면에서 동적 모델 이름 양쪽에 있는 문장을 하나로 보고 해요체로 바꿉니다. |

### `web/src/components/memory/memory-form.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/memory/memory-form.tsx:38](../../web/src/components/memory/memory-form.tsx#L38)<br>[web/src/components/memory/memory-item.tsx:44](../../web/src/components/memory/memory-item.tsx#L44) | `항상 답에 함께 넣기` | 답을 만들 때 항상 함께 넣기 | 기억을 어디에 넣는지 밝힙니다. |

### `web/src/components/shell/sidebar.tsx`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/components/shell/sidebar.tsx:44](../../web/src/components/shell/sidebar.tsx#L44) | `옮기는 중` | 이동하는 중 | 동작을 자연스러운 말로 바꿉니다. |

### `web/src/lib/stream.ts`

| 위치 | 지금 | 제안 | 까닭 |
| --- | --- | --- | --- |
| [web/src/lib/stream.ts:5](../../web/src/lib/stream.ts#L5) | `응답 스트림이 없습니다.` | 응답 연결이 없어요. | 스트림이라는 내부 용어를 쉬운 말로 바꾸고 해요체로 끝냅니다. |

## 그대로 둘 것

- 287곳입니다. 짧은 이름, 버튼 이름, 상태 표시와 이미 해요체인 문구를 포함합니다.
- 판정 대상 전체와 위치는 위 수집 기준으로 다시 추출할 수 있습니다. 변경 대상으로 표에 나오지 않은 문구는 이 범주입니다.
