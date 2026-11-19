# 새 모델 대응과 단계별 모델 이름 표시 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 스펙의 PR1(REQ1 부터 REQ5)을 다룬다. PR2(REQ6, REQ7)의 설계는 그 PR 에서 이 문서에 더한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `POST /api/v1/admin/model-catalog/refresh` 를 더한다. `GroupModelCatalogService` 가 켜진 profile 마다 `ModelOptionsService` 의 다시 읽기를 부르고 결과를 하나로 모은다. 화면은 응답이 실패가 아니면 기존 조회 둘(`GET /api/v1/admin/model-catalog`, `GET /api/v1/admin/model-hidden/candidates`)을 다시 읽어 선택지와 숨김 후보를 바꾼다 |
| REQ2 | 컨트롤러가 `requireAdmin` 을 먼저 부른다. `ModelOptionsService` 가 profile 마다 마지막 시도 시각, 마지막 성공 시각, 마지막 시도의 실패 여부를 들고, 30초 안의 요청은 Hermes 를 부르지 않고 「새로 묻지 않음」 으로 답한다. 응답의 `status` 가 `FAILED` 면 화면은 조회를 다시 하지 않아 선택지가 그대로다 |
| REQ3 | `ModelTierPicker` 의 가로 슬라이더(`TierSlider`)를 세로 radio 목록으로 바꾼다. radio 묶음과 저장 흐름(`choose`)은 그대로 두고 줄의 모양만 바꾼다 |
| REQ4 | `ModelTierService.optionsFor` 가 `ADMIN` 이 아닌 요청자에게 단계마다 `model` 과 `reasoningEffort` 를 넣고 `provider` 만 비운다. 화면은 역할과 상관없이 같은 줄을 그린다 |
| REQ5 | 예약 작업 화면(`TaskForm`)이 `GET /api/v1/chat/model-tiers` 를 읽어 선택지 문구에 모델과 강도를 붙인다. 선택지의 값과 개수는 지금 그대로다. 어느 에이전트로 읽는지는 아래 「예약 작업 화면」 이 정한다 |
| REQ6 | PR2 에서 설계한다. 방향은 하루 한 번 도는 작업이 `GroupModelCatalogService` 의 그룹 목록을 읽어 그룹 설정 줄(`group_setting`)의 새 키에 둔 「본 모델」 과 비교하고, 기존 알림(`notification`)으로 `ADMIN` 에게 알리는 것이다. 새 테이블은 만들지 않는다 |
| REQ7 | PR2 에서 설계한다. 「본 모델」 에 숨긴 모델까지 적어 되풀이와 숨김 풀기 뒤의 알림을 막는다 |

## 책임과 경계

| 모듈 | 이번에 맡는 것 |
| --- | --- |
| `chat.modelchoice.application.ModelOptionsService` | profile 하나의 다시 읽기와 그 결과(새로 읽음, 새로 묻지 않음, 실패), 마지막 성공 시각. 30초 제한의 원본이다. 새 메서드는 목록이 아니라 결과만 돌려주고 예외를 내지 않는다 |
| `chat.modelchoice.application.GroupModelCatalogService` | 그룹의 켜진 profile 전체를 다시 읽고 결과를 하나로 모은다. 한 profile 이 실패해도 나머지를 끝까지 읽는다. 모은 결과의 타입은 `chat.modelchoice.application.dto` 에 둔다 |
| `chat.modelchoice.application.ModelTierService` | 역할에 따라 단계 목록 응답에서 뺄 값. 판정은 지금처럼 `InternalValuePolicy` 를 부른다 |
| `admin.model.presentation.ModelAdminController` | 다시 읽기 경로와 관리자 검사. 응답 모양은 `ModelAdminDtos` 에 둔다 |
| `web/src/app/api/admin/model-catalog/refresh/route.ts` | 브라우저의 요청을 Control Plane 으로 넘기는 서버 라우트([rule-55](../../code-rules/rule-55.md)) |
| `web/src/lib/model-settings.ts` | 다시 읽기를 부르는 함수와 숨김 후보만 다시 읽는 함수 |
| `web/src/components/admin/model-admin-panel.tsx` | 「목록 새로 읽기」 단추와 안내. 두 절(그룹 모델 설정, 모델 숨김)이 같은 다시 읽기를 쓰므로 둘의 부모가 갖는다 |
| `web/src/components/chat/model-picker.tsx` | 세로 목록 |
| `web/src/lib/model-tiers.ts` | `<모델> · <강도>` 문구를 만드는 함수 하나. 입력창과 예약 작업 화면이 함께 쓴다 |

새 테이블, 새 설정 값, 새 패키지는 없다. Hermes 호출은 이미 있는 `HermesModelClient.readCatalog(..., refresh=true)` 를 쓴다.

다시 읽기의 기존 호출자(실행 때의 단계 해석, 단계 정의 저장)는 지금처럼 `refreshedOptionsForAgent` 를 부르고 동작이 바뀌지 않는다.
그 메서드가 안에서 새 다시 읽기 메서드를 부르도록 바꿔, 30초 제한과 캐시 갱신이 한 자리에 남는다.

## 인터페이스와 데이터

### 다시 읽기 경로

`POST /api/v1/admin/model-catalog/refresh`. 본문 없음. `ADMIN` 만 부른다.

| 응답 칸 | 뜻 |
| --- | --- |
| `status` | `REFRESHED`(켜진 profile 모두를 이번 요청에서 Hermes 에 다시 물었다), `REUSED`(한 profile 이라도 30초 제한으로 묻지 않았고 실패는 없다), `FAILED`(한 profile 이라도 다시 읽지 못했다) |
| `refreshedAt` | 켜진 profile 들의 마지막 성공 시각 가운데 가장 이른 것. 한 번도 성공하지 못한 profile 이 있으면 `null` 이다 |

profile 하나의 결과는 이렇게 정한다.

| 경우 | 결과 | 들고 있는 목록 |
| --- | --- | --- |
| 마지막 시도가 30초 이상 지났고 Hermes 가 답했다 | 새로 읽음 | 새 목록으로 바꾼다 |
| 마지막 시도가 30초 이상 지났고 Hermes 가 답하지 못했다 | 실패 | 그대로 |
| 마지막 시도가 30초 안이고 그 시도가 성공이었다 | 새로 묻지 않음 | 그대로 |
| 마지막 시도가 30초 안이고 그 시도가 실패였다 | 실패 | 그대로 |
| 마지막 시도가 30초 안이고 아직 Hermes 의 답을 기다리는 중이다 | 새로 묻지 않음 | 그대로. 앞 시도가 끝나면 그 결과로 바뀐다 |

시도의 성공과 실패는 Hermes 호출이 끝났을 때 적는다. 그래서 기다리는 중에 온 요청은 그 앞 시도의 실패 여부를 읽지 않는다.
Hermes 가 답하지 않은 것, profile key 가 없는 것처럼 읽기에서 난 `ApiException` 은 모두 실패다. 그 profile 의 목록을 한 번도 읽지 못했어도 다시 읽기는 예외를 내지 않고 실패로 답한다.

마지막 줄을 「새로 묻지 않음」 으로 두면, 실패한 직후에 다시 누른 관리자가 실패 안내 없이 옛 목록을 방금 읽은 것처럼 본다.

켜진 에이전트가 없으면 물을 profile 이 없으므로 `REFRESHED` 와 `refreshedAt` `null` 로 답한다.

화면의 처리:

| `status` | 화면 |
| --- | --- |
| `REFRESHED` | 그룹 모델 목록과 숨김 후보를 다시 읽어 선택지를 바꾸고, 다시 읽은 시각을 보인다 |
| `REUSED` | 위와 같이 다시 읽고(다른 실행이 30초 안에 바꿨을 수 있다), 30초 안에는 새로 묻지 않는다는 안내와 마지막으로 다시 읽은 시각을 보인다. 「방금 읽었다」 고 말하지 않는다. 앞 시도가 아직 도는 중일 수 있다 |
| `FAILED` 이거나 요청이 실패했다 | 조회를 다시 하지 않는다. 다시 읽지 못했고 잠시 뒤 다시 누르라는 안내를 보이고 선택지는 그대로다 |

`refreshedAt` 이 `null` 이면 시각을 그리지 않는다. 시각은 화면을 연 뒤 단추를 누르기 전에도 그리지 않는다.

단추를 누른 뒤 화면이 다시 읽는 것은 그룹 모델 목록과 숨김 후보 둘뿐이다. 저장된 단계 정의와 저장된 숨김 목록은 다시 읽지 않는다.

| 다시 읽은 뒤의 조회 | 화면 |
| --- | --- |
| 그룹 모델 목록을 받았다 | 단계별 모델의 선택지만 바꾼다 |
| 숨김 후보를 받았다 | 숨김 폼을 그린 채 후보만 바꾼다. 폼을 다시 만들지 않아 저장 전에 고친 체크가 남는다([rule-69](../../code-rules/rule-69.md)) |
| 둘 가운데 하나가 실패했거나 목록 없이 왔다 | 그 절은 앞 선택지를 그대로 두고 실패 안내를 보인다. 선택지를 비우지 않는다 |

「그룹 모델 설정」 의 고치던 값(저장 전 단계 선택)도 지우지 않는다. `GroupTierSettings` 는 부모가 올린 다시 읽기 횟수를 받아 목록만 다시 읽는다.

### 단계 목록 응답

`GET /api/v1/chat/model-tiers` 의 단계 한 줄에서 `MEMBER` 역할에게 빼는 값이 `provider`, `model`, `reasoningEffort` 에서 `provider` 하나로 줄어든다.
표의 원본은 [`chat.md`](../chat.md) 의 「역할에 따라 응답에서 빼는 값」 이고 같은 PR 에서 고친다.
`ADMIN` 응답은 바뀌지 않는다. `ADMIN` 응답에서 provider 를 비운 옛 정의를 에이전트 기본 provider 로 채우는 동작도 그대로다.

### 세로 목록

- radio 묶음(`role="radiogroup"`)과 줄마다의 `input type="radio"`, `data-testid="model-tier-<단계>"` 를 유지한다. 방향키 이동과 브라우저 테스트의 고르는 방법이 그대로다.
- 줄은 단계 이름, 그 아래 `<모델> · <강도>`, 오른쪽의 체크 표시다. 모델이 없는 응답(옛 Control Plane)에는 둘째 줄을 그리지 않는다.
- 둘째 줄은 한 줄로 두고 넘치면 줄임표로 끝낸다. 단계 이름은 줄이지 않는다.
- 팝오버 폭과 여는 방법, 제목과 안내 문구는 지금 그대로다.
- 팝오버의 `data-testid` 는 `model-tier-slider` 에서 `model-tier-list` 로 바꾸고 브라우저 테스트와 화면 캡처 이름을 함께 고친다. 슬라이더가 아닌 것을 슬라이더라 부르는 이름을 남기지 않는다.
- 「모델과 강도는 역할과 상관없이 그리지 않는다」 고 적은 주석(`ModelTierService.optionsFor`, `model-picker.tsx`)을 함께 고친다.

### 문구 함수

`tierModelLine(tier)` 가 모델과 강도를 `<모델> · <강도>` 로 잇는다. 강도가 비면 모델만, 모델이 비면 `null` 이다.
예약 작업 화면은 `<단계 이름> · ` 뒤에 이 값을 붙이고, `null` 이면 단계 이름만 쓴다.

### 예약 작업 화면

단계 정의는 그룹의 값이라 어느 에이전트로 읽어도 같다. 그런데 단계 목록 경로는 요청자가 대화를 시작할 수 있는 에이전트 코드를 요구하고, 꺼진 에이전트의 작업을 고칠 때는 그 작업의 에이전트로 읽을 수 없다.
그래서 화면이 이미 읽는 에이전트 목록(대화를 시작할 수 있는 에이전트)의 첫 에이전트로 한 번 읽는다. 작업에 고른 에이전트를 쓰지 않는다.

| 경우 | 화면 |
| --- | --- |
| 에이전트 목록이 아직 오지 않았거나 비었다 | 조회하지 않고 단계 이름만 보인다 |
| 에이전트 목록이 왔다 | 첫 에이전트로 한 번 읽어 문구를 붙인다. 작업의 에이전트를 바꿔도 다시 읽지 않는다 |
| 단계 목록을 읽지 못했다 | 단계 이름만 보인다. 저장을 막지 않는다 |
| 화면을 떠난 뒤 응답이 왔다 | 버린다 |

## 실패와 일관성

- **다시 읽기는 트랜잭션 밖에서 한다.** DB 를 쓰지 않고 `ModelOptionsService` 의 메모리 캐시만 바꾼다.
- **일부 profile 만 실패하면 성공한 profile 의 캐시는 새 값으로 남는다**(스펙 DECISION13). 그룹 목록은 교집합이라, 화면을 새로 열어 보이는 선택지는 모든 profile 이 가진 모델로만 달라진다.
- **겹친 요청**: 두 관리자가 동시에 눌러도 profile 마다 마지막 시도 시각을 먼저 적으므로 대부분 한쪽만 Hermes 를 부른다. 같은 순간에 둘 다 부르는 경우는 막지 않는다. 가족 몇 명 규모라 겹쳐도 비용이 작다(`ModelOptionsService` 의 기존 방침).
- **시간**: `refresh=true` 는 Hermes 가 provider 마다 실제 조회를 기다려 느리다. 읽기 timeout 은 기존 30초를 쓰고 profile 을 차례로 읽으므로 최악에는 profile 수에 30초를 곱한 시간이 걸린다. 화면은 그동안 단추를 막고 진행 중임을 보인다.
- **도중에 끊긴 요청**: 앞단이 요청을 먼저 끊으면 화면은 실패 안내를 보이지만 Control Plane 의 캐시는 바뀌었을 수 있다. 화면을 새로 열거나 30초 뒤 다시 누르면 반영된다.
- **호환과 배포 순서**: 응답에 값을 더하고 경로를 더하는 변경이라 backend 를 먼저 배포해도 옛 화면이 깨지지 않는다. 새 화면이 옛 backend 를 만나면 둘째 줄이 `ADMIN` 에게만 보이고 다시 읽기는 실패 안내가 뜬다. 둘은 한 배포로 함께 나간다.
- **Hermes 계약**: 주고받는 모양을 바꾸지 않는다. 가짜 Hermes 도 고치지 않는다. 그래서 배포 뒤 왕복은 스펙의 「배포 뒤 확인」 만 본다.

## 검증 전략

| ID | 어디서 확인하나 |
| --- | --- |
| REQ1 | backend: 가짜 `HermesModelClient` 가 `refresh=true` 에만 새 모델을 주게 해, 다시 읽기 전후의 그룹 목록과 단계 저장을 본다. 브라우저 테스트: 「목록 새로 읽기」 를 누르기 전후의 선택지 |
| REQ2 | backend: `MEMBER` 요청이 `FORBIDDEN` 이고 Hermes 호출이 0 번인 것, 고정 시계로 30초 안의 재요청이 `REUSED` 이고 Hermes 호출이 늘지 않는 것, 한 profile 의 실패가 `FAILED` 이고 나머지 profile 은 새 목록을 들고 있는 것, 한 번도 읽지 못한 profile 의 실패가 예외가 아니라 `FAILED` 인 것, 실패 직후 재요청이 `FAILED` 인 것, 앞 시도가 Hermes 를 기다리는 동안의 요청이 `REUSED` 인 것. 브라우저 테스트: `FAILED` 응답 뒤 선택지가 그대로이고 실패 안내가 보이는 것, `REUSED` 안내, 다시 읽은 뒤에도 저장 전 단계 선택과 숨김 체크가 남는 것 |
| REQ3 | 브라우저 테스트(`model-choice.spec.ts`): 세로 목록의 줄 문구와 고른 줄 표시, 줄을 눌러 저장, 방향키 저장, 폭 360px 에서 단계 이름이 잘리지 않는 것 |
| REQ4 | backend: `MEMBER` 의 단계 목록 응답에 `model`, `reasoningEffort` 가 있고 `provider` 가 `null` 인 것. 브라우저 테스트: `MEMBER` 응답 모양으로 목록에 모델이 보이는 것 |
| REQ5 | 브라우저 테스트(`tasks.spec.ts`): 채운 단계와 비운 단계, 「기본값」 의 선택지 문구 |

스펙의 「배포 뒤 확인」 은 운영에서 코디네이터가 본다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 다시 읽기 응답이 목록을 담는가 | 담지 않는다. 결과와 시각만 주고 화면이 기존 조회 둘을 다시 읽는다 | 작성자 | 목록의 모양과 「목록 없음」 처리가 이미 그 두 조회에 있다. 응답에 담으면 같은 조립이 두 벌이 된다 |
| DECISION2 | 「목록 새로 읽기」 단추의 자리 | 「모델」 화면의 두 절 위에 하나 둔다 | 작성자 | 같은 다시 읽기가 단계별 모델 선택지와 숨김 후보를 함께 바꾼다. 절마다 두면 한쪽만 바뀐 것처럼 보인다 |
| DECISION3 | 실패 직후 30초 안의 재요청 | `FAILED` 로 답한다 | 작성자 | 스펙 REQ2 의 「새로 묻지 않았다는 안내」 는 방금 읽은 목록이 있을 때의 것이다. 실패를 「방금 읽음」 으로 보이면 6.2 가 없는 원인을 잘못 짚는다 |
| DECISION4 | 일부 profile 이 30초 제한에 걸렸을 때의 `status` | `REUSED` | 작성자 | 모든 profile 을 이번에 물은 것이 아니면 「방금 계정에 물어본 결과」 라고 말할 수 없다 |
| DECISION5 | 세로 목록의 구현 | radio 묶음을 유지하고 모양만 바꾼다. 메뉴 부품(`DropdownMenu`)으로 바꾸지 않는다 | 작성자 | 방향키로 옮기면 바로 저장하는 동작과 겹친 저장의 처리, 브라우저 테스트가 radio 에 맞춰져 있다 |
| DECISION6 | 결정 기록 | 새 ADR [ADR-20261119 / tier-model-names](../../adr/ADR-20261119-tier-model-names.md) 를 만든다 | 작성자 | 스펙의 「결정 기록 연결」 은 두 ADR 을 고친다고 적었다. 과거 결정을 덮어쓰지 않는 규칙에 따라 새 ADR 이 [ADR-063](../../adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md) 에서 단계 목록의 모델과 강도를 빼던 부분을, [ADR-20261101 / model-setting](../../adr/ADR-20261101-model-setting.md) 에서 슬라이더로 고르던 부분을 대체하고, 두 ADR 에는 대체된 부분만 표시한다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 단계를 고르는 곳의 모델과 강도 표시, 세로 목록 | domain-policy | [ADR-20261119 / tier-model-names](../../adr/ADR-20261119-tier-model-names.md) | [`chat.md`](../chat.md) 의 「역할에 따라 응답에서 빼는 값」, [`model-usage.md`](../model-usage.md) 의 「모델을 고를 때」 |
| 관리자 화면의 목록 다시 읽기 | 필요 없음 | 기존 다시 읽기를 관리자 화면에서 부르는 길을 더한 것이고 되돌릴 때 함께 바꿀 데이터나 계약이 없다 | [`model-usage.md`](../model-usage.md) 의 「그룹 모델 목록」 |
