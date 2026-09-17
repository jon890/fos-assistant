# Phase 02. 대화 화면을 채팅처럼 만든다

**Execution profile**: standard

## 목표

내가 보낸 줄과 비서가 답한 줄이 한눈에 갈리게 한다.
ChatGPT 의 배치를 따른다.

지금은 둘 다 화면 전체 폭을 쓰고 둘 다 왼쪽 정렬이다.
차이가 배경이 회색인지 테두리가 있는지 뿐이라 채팅으로 읽히지 않는다.

**범위 외**

- 스트리밍 흐름을 바꾸지 않는다. 오는 것을 어떻게 보이느냐만 다룬다.
- 마크다운을 그리는 방식을 바꾸지 않는다. 답 안의 HTML 은 계속 그리지 않는다.
- 사용량과 관리 화면은 phase-03 이 한다.
- 메시지를 지우거나 다시 보내는 기능은 만들지 않는다.

## 컨텍스트

**phase-01 이 끝나 있어야 한다.** 브랜드 색 토큰과 단추 조각이 있어야 한다.

대화 화면은 이미 부품으로 나뉘어 있다. 파일을 새로 나누지 않는다.

| 파일 | 맡는 것 |
| --- | --- |
| `web/src/components/chat-panel.tsx` | 상태와 호출 |
| `web/src/components/chat/message-list.tsx` | 목록과 아래로 따라가기 |
| `web/src/components/chat/message-bubble.tsx` | 한 줄 |
| `web/src/components/chat/composer.tsx` | 입력창과 보내기 |
| `web/src/components/chat/conversation-drawer.tsx` | 좁은 화면의 서랍 |
| `web/src/components/chat/conversation-list.tsx` | 대화 목록 |
| `web/src/components/chat/run-status.tsx` | 도구 호출과 기다림 |
| `web/src/components/chat/markdown.tsx` | 마크다운 |

**근거 문서**: `docs/code-architecture.md` 의 「우리 화면의 정체성」 절,
`docs/flow.md` 의 「화면 배치」 와 「기다리는 동안 보이는 것」 절

## 의도 메모

- 비서의 답만 열 전체 폭을 쓴다. 표와 코드 블록이 오기 때문이다.
  좁은 말풍선에 넣으면 그 안에서 가로로 밀어야 읽힌다.
- 내 말은 오른쪽 말풍선에 넣는다. 짧은 문장이라 폭이 필요하지 않다.
- 대화 열에 최대 폭을 준다. 넓은 화면에서 한 줄이 끝까지 늘어나면 눈이 줄을 놓친다.
- 보낸 사람 이름을 말풍선 안에 넣지 않는다. 오른쪽에 있다는 것이 이미 내 말이라는 뜻이다.
  여러 사람이 같은 대화를 쓰게 되면 그때 이름을 다시 넣는다. 지금은 대화마다 주인이 한 사람이다.
- 비서 줄에는 표시를 하나 둔다. 답이 여러 개 이어질 때 어디서 새 답이 시작하는지 보여야 한다.
- 시각을 늘 보이지 않는다. 말풍선에 마우스를 올리거나 누를 때만 보인다.
  모든 줄에 시각이 붙으면 읽을 것이 두 배가 된다.
- 입력창의 보내기를 알약 안으로 넣는다. 입력창과 단추가 따로 있으면 자리를 두 번 쓴다.
- 입력창이 비어 있으면 보내기를 누를 수 없게 한다. 지금은 눌러도 아무 일이 없다.

## 작업 항목

### 1. 대화 열에 최대 폭을 준다

`chat-panel.tsx` 와 `message-list.tsx` 를 고친다.

- 메시지와 입력창이 같은 최대 폭을 쓰고 가운데 정렬한다.
- 넓은 화면에서 그 열이 대화 목록 오른쪽 남은 자리의 가운데에 온다.
- 좁은 화면에서는 좌우 여백만 두고 폭을 다 쓴다.

대화 목록 칸은 `surface-raised` 를 배경으로 쓴다. 본문과 배경이 갈려야 열이 보인다.

### 2. 내 말을 오른쪽 말풍선으로 바꾼다

`message-bubble.tsx` 를 고친다.

- `USER` 줄은 오른쪽 정렬이다. 폭은 열의 70% 까지다.
- 배경은 `brand-soft` 다. 모서리는 알약에 가깝게 둥글다.
- 보낸 사람 이름을 말풍선 안에 넣지 않는다.
- 사용자가 쓴 글을 마크다운으로 그리지 않는다. 지금 동작을 유지한다.

### 3. 비서 답을 전체 폭으로 바꾼다

- `ASSISTANT` 줄은 열 전체 폭이다. 배경과 테두리가 없다.
- 왼쪽에 표시를 하나 둔다. 원 안에 글자 하나로 만든다. 그림 파일을 쓰지 않는다.
- 그 표시 옆에 `비서` 를 적는다.
- 답이 길어도 줄 간격이 충분해야 한다.

### 4. 시각을 눌렀을 때만 보인다

- 말풍선에 마우스를 올리거나 키보드로 고르면 시각이 보인다.
- 비서 줄에는 시각과 함께 소요 시간을 보인다. `4.6초` 형태다.
  `web/src/lib/format.ts` 의 `formatDuration` 을 쓴다.
- 소요 시간은 실행 기록에 있다. 메시지 조회가 그 값을 주지 않으면 이 항목을 빼고 보고에 적는다.

### 5. 입력창을 알약 하나로 만든다

`composer.tsx` 를 고친다.

- `textarea` 와 보내기 단추를 하나의 둥근 테두리 안에 넣는다.
- 보내기는 원형이고 브랜드 색으로 꽉 찬다. 위쪽 화살표 아이콘을 넣는다.
  아이콘은 인라인 SVG 로 만든다. 아이콘 라이브러리를 더하지 않는다.
- `aria-label` 을 `보내기` 로 둔다. 아이콘만 있으면 읽어 주는 이름이 필요하다.
- 입력창이 비어 있거나 보내는 중이면 누를 수 없다.
- 지금 동작을 모두 유지한다. `Enter` 로 보내고 `Shift+Enter` 로 줄을 바꾸고,
  한글을 조합하는 중에는 보내지 않고, 실패하면 쓴 문장을 되돌린다.
- 글자 크기를 16px 이상으로 둔다. iOS Safari 가 그보다 작으면 화면을 확대한다.

### 6. 기다림 표시를 다듬는다

`run-status.tsx` 를 고친다.

- 비서 표시 옆에 점 세 개가 차례로 밝아진다. 지금 동작을 유지한다.
- 점의 색을 브랜드 색으로 바꾼다.
- 도구를 부르는 중이면 그 이름을 한 줄로 보인다. 지금 동작을 유지한다.
- `prefers-reduced-motion` 을 켠 사용자에게는 움직이지 않는다. 지금 동작을 유지한다.

### 7. 이 phase 를 검증하는 브라우저 테스트

`test/browser/chat.spec.ts` 에 더한다. 새 파일을 만들지 않는다.

- `USER` 줄의 오른쪽 끝이 `ASSISTANT` 줄의 오른쪽 끝과 같고,
  `USER` 줄의 왼쪽 끝이 `ASSISTANT` 줄의 왼쪽 끝보다 오른쪽에 있다.
  이것이 오른쪽 정렬과 전체 폭을 함께 확인하는 방법이다.
- `USER` 말풍선의 폭이 열 폭의 70% 이하다.
- 보내기 단추의 배경이 투명이 아니고 브랜드 색이다.
- 입력창이 비어 있으면 보내기가 `disabled` 다.
- 입력창과 보내기 단추가 같은 테두리 안에 있다.
  단추의 사각형이 입력창을 감싸는 요소의 사각형 안에 들어간다.

기존 테스트가 모두 그대로 통과해야 한다.
특히 답 안의 HTML 이 실행되지 않는 것과 위로 올려 읽을 때 자리를 지키는 것이다.

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
```

```bash
# cwd: 저장소 root
grep -rn "dangerouslySetInnerHTML\|rehype-raw" web/src/ && echo "실패" || echo "통과"
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

브라우저에서 390px 과 1280px 을 직접 보고 그 결과를 보고에 적는다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat/conversation-list.tsx` | 수정 |
| `web/src/components/chat/run-status.tsx` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |

## 끝낸 뒤

`tasks/plan007-identity/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
