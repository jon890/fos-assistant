# Phase 03. 나머지 화면에 같은 정체성을 입힌다

**Execution profile**: standard

## 목표

머리와 로그인 화면과 사용량과 관리 화면이 대화 화면과 같은 인상을 갖게 한다.
좁은 화면에서 제목이 잘리는 결함을 고친다.

로그인 화면은 아직 배치를 손대지 않았다.
가족이 처음 보는 화면인데 왼쪽에 붙은 글자 셋뿐이다.

**범위 외**

- 새 기능을 만들지 않는다. 보이는 것만 바꾼다.
- `에이전트 관리` 메뉴를 역할에 따라 보이는 것은 다른 plan 이 이미 했다.
  그 동작을 지운 채 머리를 다시 쓰지 않는다. **고치기 전에 지금 `layout.tsx` 를 읽는다.**

## 컨텍스트

**phase-01 과 phase-02 가 끝나 있어야 한다.**

머리에 있는 것이 지금 이렇다.

| 요소 | 지금 |
| --- | --- |
| 제목 | 좁은 화면용 짧은 이름과 넓은 화면용 긴 이름을 겹쳐 둔다 |
| 메뉴 | 대화, 사용량, 에이전트 관리 |
| 밝기 단추 | 오른쪽 |

**제목이 좁은 화면에서 `비서` 로 잘린다.** 실측으로 확인했다.
요소의 글자는 `비서우리집 비서` 이고 폭이 24px 이라 앞의 두 글자만 보인다.
넓은 화면에서는 `우리집 비서` 로 제대로 나온다.
짧은 이름과 긴 이름을 겹쳐 두고 하나를 숨기는 방식이 좁은 화면에서 걸리지 않았다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 머리 | `web/src/app/layout.tsx` |
| 로그인 화면 | `web/src/app/signin/page.tsx` |
| 사용량 화면 | `web/src/app/usage/page.tsx` 와 `web/src/components/usage/` |
| 관리 화면 | `web/src/app/admin/agents/` 와 `web/src/components/admin/` |
| 단추 조각 | `web/src/components/ui/button.tsx` |

**근거 문서**: `docs/code-architecture.md` 의 「우리 화면의 정체성」 절

## 의도 메모

- 제목 잘림을 숨기기로 고치지 않는다. 겹쳐 둔 것 자체를 없앤다.
  두 이름을 겹쳐 두면 읽어 주는 도구가 둘을 이어 읽는다. 실제로 그렇게 되어 있다.
- 로그인 화면에 정체성을 가장 많이 쓴다. 가족이 처음 보는 화면이다.
- 사용량과 관리 화면은 이미 읽힌다. 색과 단추만 맞춘다. 배치를 다시 만들지 않는다.
- 관리 화면의 가족 공개 확인은 그대로 둔다. 그 흐름을 손대지 않는다.

## 작업 항목

### 1. 제목 잘림을 고친다

`layout.tsx` 를 고친다.

- 짧은 이름과 긴 이름을 겹쳐 두지 않는다. 이름은 하나만 그린다.
- 폭이 부족하면 글자가 아니라 표시만 남긴다.
  표시는 원 안의 글자 하나로 만든다. phase-02 의 비서 표시와 같은 모양을 쓴다.
- 링크의 `aria-label` 은 늘 `우리집 비서 홈` 이다.

고친 뒤 확인한다. 요소가 읽는 글자가 `우리집 비서` 하나여야 한다.

### 2. 머리를 다듬는다

- 지금 있는 화면을 브랜드 색으로 표시한다. 지금은 배경만 회색으로 바뀐다.
- 메뉴 사이 간격을 늘린다.
- 로그인한 사람 이름이 있으면 오른쪽에 둔다. 이미 그렇게 되어 있으면 그대로 둔다.
- 좁은 화면에서 메뉴가 줄바꿈되지 않아야 한다. 이름을 줄이거나 표시로 바꾼다.

### 3. 로그인 화면을 다시 만든다

`signin/page.tsx` 를 고친다.

- 화면 가운데에 둔다. 위에서 아래로 흘려 두지 않는다.
- 카드 하나에 담는다. 그 안에 표시와 이름과 한 줄 설명과 단추를 둔다.
- 단추는 `ui/button.tsx` 의 `primary` 를 쓴다. 브랜드 색으로 꽉 찬다.
- 이 화면에는 머리의 메뉴를 그리지 않는다. 로그인하기 전에 누를 수 있는 것이 없다.
  지금은 `대화` 와 `사용량` 과 `에이전트 관리` 가 함께 보인다.
- 밝기 단추는 남긴다. 어두운 곳에서 여는 사람이 있다.

`layout.tsx` 가 경로를 보고 메뉴를 그릴지 정한다.
서버 컴포넌트라 `next/headers` 나 자식 화면이 알려 주는 방법 중 하나를 쓴다.
어느 쪽을 골랐는지와 그 이유를 한국어 주석으로 남긴다.

### 4. 사용량과 관리 화면의 색과 단추를 맞춘다

- 손으로 적은 단추를 `ui/button.tsx` 로 바꾼다.
- 사용량 화면의 합계 세 값 중 금액에 브랜드 색을 쓴다. 나머지는 본문 색이다.
  셋을 모두 색칠하면 무엇이 중요한지 사라진다.
- 관리 화면의 `가족 공개로 변경` 단추는 `secondary` 를 쓴다.
  브랜드 색으로 꽉 찬 단추로 만들지 않는다. 공개 범위를 넓히는 것이 권하는 행동이 아니다.
- `사용 중지` 는 `ghost` 를 쓴다.

### 5. 이 phase 를 검증하는 브라우저 테스트

`test/browser/nav.spec.ts` 에 더한다. 그 파일이 없으면 만든다.

- 머리의 홈 링크가 읽는 글자가 `우리집 비서` 하나다. 두 이름이 이어져 있지 않다.
- 390px 에서 머리의 요소가 한 줄에 들어간다. 머리 높이가 두 줄이 되지 않는다.
- 로그인 화면에 `대화` 와 `사용량` 메뉴가 보이지 않는다.
- 로그인 화면의 카드가 화면 가운데에 있다. 왼쪽 여백과 오른쪽 여백의 차가 8px 이하다.
- 로그인 단추의 배경이 브랜드 색이고 투명이 아니다.

로그인 화면은 세션 없이 열어야 한다.
하네스가 세션을 넣는 구조라면 넣지 않고 여는 방법을 더한다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
node test/e2e/run.ts
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

```bash
# cwd: 저장소 root
grep -rn "requireAdmin" backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java
```

**`requireAdmin` 이 남아 있어야 한다.** 화면을 고치면서 서버 검사를 건드리지 않는다.

브라우저에서 390px 과 1280px 을 직접 보고 그 결과를 보고에 적는다.
로그인 화면도 두 폭에서 함께 본다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/app/signin/page.tsx` | 수정 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/admin/agent-form.tsx` | 수정 |
| `test/browser/nav.spec.ts` | 수정 또는 신규 |

## 끝낸 뒤

`tasks/plan007-identity/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
