# 개발 문서와 로드맵 기술 설계

스펙은 [`spec.md`](spec.md)다. 이 설계는 PR1(REQ1~REQ6, REQ9)을 다룬다. 진행 보드(REQ7, REQ8, REQ10)는 스펙 DECISION18 로 취소해 설계하지 않는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `/dev-docs` 화면을 일반 화면으로 두고 로그인만 확인한다. 사이드바 「고급」 에 「개발 문서」 링크(아이콘 `BookOpen`)를 더하고 「고급」 의 정의를 「매일 쓰지 않는 일반 화면」 으로 넓힌다 |
| REQ2 | 웹 이미지가 빌드 인자 `SOURCE_COMMIT` 을 받아 환경 변수로 둔다. 화면 위에 앞 7자리와 GitHub 커밋 링크, 문서마다 같은 커밋의 파일 링크를 둔다. 고치는 단추는 없다 |
| REQ3 | 서버의 문서 목록 모듈이 이미지에 담긴 문서 폴더를 읽어 묶음 넷의 목록을 만든다. 찾기는 브라우저에서 목록 이름과 파일 이름에 그 글자가 든 줄만 남긴다 |
| REQ4 | 채팅의 `Markdown` 과 같은 react-markdown 기본값(HTML 을 그리지 않음)으로 그린다. 링크 부품이 상대 경로를 저장소 경로로 풀어 목록에 있으면 화면 안, 없으면 같은 커밋의 GitHub 로 보낸다. 제목에 GitHub 와 같은 규칙의 id 를 붙여 `#절` 이 맞게 한다 |
| REQ5 | ` ```mermaid ` 블록만 브라우저에서 `mermaid` 를 늦게 불러 그린다. 기본 설정에 `look: handDrawn` 을 주고 블록 머리의 설정이 그 위를 덮는다. mermaid 가 손그림을 지원하지 않는 그림 종류(sequence 등)는 기본 모양으로 그린다. 실패하면 원문과 「그림을 그리지 못했어요」 |
| REQ6 | `docs/prd.md` 에 「로드맵」 절(지금, 다음, 나중 표)을 더하고, `/dev-docs` 첫 화면이 그 절만 잘라 같은 Markdown 부품으로 그린다 |
| REQ10 | 취소(스펙 DECISION18) |
| REQ7 | 취소(스펙 DECISION18). 로드맵 갱신은 코디네이터가 `docs/prd.md` 를 PR 로 고친다 |
| REQ8 | 취소(스펙 DECISION18) |
| REQ9 | 이미지에 담는 것은 루트 `docs/` 와 `backend/docs/`, `web/docs/`, `hermes/docs/` 뿐이다. 화면은 그 목록에 든 파일만 연다 |

## 책임과 경계

`web` 하나가 맡는다. Control Plane 과 Hermes 를 바꾸지 않는다.

| 자리 | 맡는 것 |
| --- | --- |
| `web/src/lib/dev-docs/` 의 `catalog.ts`, `read.ts` (서버 전용, `node:fs`) | 묶음 넷의 목록 만들기, 경로를 목록에 대조해 파일 읽기, `prd.md` 의 「로드맵」 절 자르기 |
| `web/src/lib/dev-docs/` 의 `source.ts`, `links.ts`, `filter.ts`, `headings.ts` (브라우저 부품도 import, `fs` 없음) | 문서 폴더 위치와 커밋 값, GitHub 주소, 링크 풀기, 찾기, 제목 id(rehype 플러그인). mermaid 요소 id 는 2026-11-20 에 대화 답과 함께 쓰려고 `web/src/lib/mermaid.ts` 로 옮겼다 |
| `web/src/app/dev-docs/` | 로그인 확인 뒤 서버 컴포넌트로 목록과 본문을 읽어 넘긴다. 첫 화면은 로드맵, `/dev-docs/<저장소 경로>` 는 그 문서 |
| `web/src/components/dev-docs/` | 목록과 찾기 칸(브라우저), 문서 본문 Markdown. mermaid 블록은 2026-11-20 에 `web/src/components/ui/mermaid-block.tsx` 로 옮겼다 |
| `web/Dockerfile` | 추가 빌드 문맥에서 문서 폴더를 runner 단계로 복사하고 `SOURCE_COMMIT` 을 받는다 |
| 운영 배포(`fos-home-infra`) | web 빌드에 저장소 root 를 추가 빌드 문맥 `repo` 로, 배포 커밋 40자리를 빌드 인자 `SOURCE_COMMIT` 으로 준다. 이 저장소 밖의 작업이다 |

브라우저는 Control Plane 을 부르지 않으므로 [rule-55](../../code-rules/rule-55.md) 의 API 경로가 늘지 않는다. 문서 읽기는 서버 컴포넌트에서만 한다.
함께 고치는 문서:

- `web/docs/code-architecture.md`: 화면 목록에 `/dev-docs`, 사이드바 그림과 아이콘 표에 「개발 문서」, 「고급」 의 정의. `main-nav.tsx` 의 `ADVANCED_LINKS` 주석도 같은 정의로 고친다
- `docs/features/dev-overview.md`: 지금 동작하는 것(화면, 목록 규칙, 이미지에 담는 방식)
- `docs/prd.md`: 「로드맵」 절과 범위 표의 한 줄
- `web/test/browser/web-server.ts`: 브라우저 테스트 서버에 `DEV_DOCS_ROOT` 를 넘긴다

채팅의 `Markdown`(`web/src/components/chat/markdown.tsx`)은 링크를 모두 새 탭으로 보내므로 그대로 쓰지 않는다. mermaid 블록은 2026-11-20 부터 두 부품이 함께 쓴다([`answer-quality/spec.md`](../answer-quality/spec.md) 의 REQ19). 표, 목록, 코드 블록의 모양은 같은 class 를 쓴다. 공통 부분을 나누는 리팩터링은 이 PR 에서 하지 않는다.

## 인터페이스와 데이터

### 문서를 담는 자리

- 운영 이미지: runner 단계의 `/app/dev-docs/` 아래에 저장소와 같은 상대 경로로 둔다(`/app/dev-docs/docs/prd.md`, `/app/dev-docs/backend/docs/...`). 환경 변수 `DEV_DOCS_ROOT=/app/dev-docs`.
- 로컬 개발과 브라우저 테스트: `DEV_DOCS_ROOT` 로만 정한다. `process.cwd()` 나 번들 위치로 짐작하지 않는다. standalone 서버는 시작할 때 작업 폴더를 자기 폴더로 바꾸기 때문이다. 브라우저 테스트 서버(`web/test/browser/web-server.ts`)가 저장소 root 를 넘기고, `pnpm dev` 는 개발자가 넘긴다. 값이 없으면 「개발 문서를 담지 않고 띄운 서버예요」 를 보인다.
- 커밋: `SOURCE_COMMIT` 환경 변수. 40자리 소문자 16진수(git 이 내는 꼴)가 아니면 없는 것으로 본다. 없으면(로컬, CI 의 `pnpm build`) 화면 위에 「커밋 정보 없음」 을 보이고 GitHub 링크는 `main` 을 가리킨다.
- 저장소 주소는 `https://github.com/jon890/fos-assistant` 상수다. 공개 저장소의 주소라 운영 정보가 아니다.

Next.js `standalone` 출력은 코드가 import 하지 않는 파일을 담지 않으므로, 문서는 빌드 결과에 넣지 않고 runner 단계에 따로 복사해 실행 중에 `fs` 로 읽는다. 문서 Markdown 은 지금 약 4MB 이고 메모리에는 목록(경로, 제목, 상태)만 둔다. 본문은 요청마다 읽는다. 파일 경로는 환경 변수에서만 만들고, Next.js 파일 추적이 동적 경로 때문에 web 폴더 전체를 standalone 결과에 넣지 않게 한다(필요하면 `turbopackIgnore` 주석). 빌드 뒤 standalone 크기가 늘지 않았는지 확인한다.

### 목록

| 묶음 | 원본 | 이름 |
| --- | --- | --- |
| 제품 | `docs/prd.md`, `docs/code-architecture.md`, `<모듈>/docs/code-architecture.md`, `backend/docs/data-schema.md`, `hermes/docs/hermes-contract.md` 가운데 있는 것 | 첫 `#` 제목 |
| 기능 | `docs/features/*.md`. 같은 이름 폴더의 `spec.md`, `design.md` 를 아래에 둔다. `tasks/` 는 넣지 않는다 | 첫 `#` 제목 |
| 결정 | 루트와 `backend`, `web`, `hermes` 의 `docs/adr/INDEX.md`. 나눔마다 그 `INDEX.md` 가 첫 줄이고, 그 아래는 첫 열이 ADR 파일 링크인 표의 줄 순서다. 「보관」 제목 앞의 표는 본 목록, 뒤의 표는 그 나눔의 「보관」 이다. 상태는 마지막 칸의 첫 마침표 앞 글자를 링크 없는 글로만 보인다(루트는 네 칸, 모듈은 세 칸이라 마지막 칸으로 읽는다) | 표의 제목 칸 |
| 코드 규칙 | `docs/code-rules/INDEX.md` 와 `rule-N.md`(번호 순) | 첫 `#` 제목 |

목록은 프로세스마다 한 번 만들어 둔다(이미지 안의 문서는 바뀌지 않는다). 개발 서버에서는 요청마다 만든다.

### 주소와 링크

- `/dev-docs`: 로드맵과 목록. `/dev-docs/<저장소 경로>`: 그 문서와 목록. 경로가 목록에 없으면 404 다. `..` 나 절대 경로는 목록에 없으므로 같은 404 다.
- 링크와 주소의 경로는 퍼센트 인코딩을 풀고 정규화한 뒤 목록과 대조한다. ADR 파일 이름 대부분이 한글이라 react-markdown 이 넘기는 인코딩된 주소를 그대로 대조하면 모두 목록 밖이 된다.
- 링크 풀기: 문서 경로 기준으로 상대 링크를 저장소 경로로 정규화한다. 목록에 있으면 `/dev-docs/<경로>#절`, 저장소 안이지만 목록에 없으면 `<저장소>/blob/<커밋>/<경로>` 새 탭, `http(s)` 는 새 탭, `#절` 만이면 같은 문서 안. 저장소 root 밖으로 나가는 링크는 GitHub 저장소 첫 화면으로 보낸다.
- 제목 id 는 `github-slugger` 를 web 의존성에 더해 만들고, 문서마다 새 slugger 로 시작해 같은 이름 제목의 `-1` 이 GitHub 와 같게 붙는다. 문서들이 GitHub 의 `#절`(한글 포함)을 쓰고 있어 같은 규칙이어야 맞는다. 다른 문서의 `#절` 로 갈 때는 해시를 풀어 제목을 찾는다.
- 이미지(`![](...)`)는 그리지 않고 「그림: <대체 글>」 링크로 바꾼다. 저장소 안 그림은 같은 커밋의 GitHub 파일로, 바깥 그림은 그 주소로 새 탭에서 연다. 화면을 열 때 바깥으로 요청이 나가지 않는다.
- react-markdown 의 기본 주소 검사(`javascript:` 등을 막는 `urlTransform`)는 끄지 않는다.

### 로드맵

`docs/prd.md` 의 `## 로드맵` 부터 다음 `##` 앞까지를 자른다. 절이 없으면 「로드맵이 아직 없어요」 를 보인다. 표의 해석 규칙은 두지 않는다(스펙 DECISION14).
첫 내용은 지금 보드와 기능 문서에서 큰 작업을 골라 PR1 이 채우고, 사용자가 PR 에서 확인한다.

### mermaid

- `mermaid` 패키지를 web 의존성에 더하고 mermaid 블록이 있는 문서에서만 동적 import 한다. 다른 화면의 번들은 늘지 않는다.
- `mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "default", look: "handDrawn" })`. 블록 머리(`---\nconfig: ...\n---`)의 설정이 이 기본값을 덮지만 `securityLevel` 은 mermaid 가 문서 설정으로 바꾸지 못하게 막는다. `strict` 는 라벨의 HTML 을 정리해 위험한 태그를 빼고(`<br/>` 같은 줄바꿈은 남는다) 클릭 동작을 막는다([rule-57](../../code-rules/rule-57.md) 의 경계와 같다).
- 손그림은 mermaid 가 지원하는 그림 종류(flowchart, state 등)에만 듣는다. 지금 문서의 그림은 대부분 sequence 라 기본 모양으로 그려진다(스펙 REQ5).
- 그림은 흰 바탕 상자 안에 그린다. 어두운 테마에서도 같은 바탕이다(스펙 DECISION16).
- 블록마다 고유 id 로 `render` 를 부른다. 실패하면 mermaid 가 `body` 에 남긴 임시 요소를 지우고 그 블록만 원문 코드 블록과 오류 문구로 바꾼다.

## 실패와 일관성

- `DEV_DOCS_ROOT` 가 없거나 그 아래 `docs/` 폴더가 없으면 화면은 「개발 문서를 담지 않고 띄운 서버예요」 를 보이고 다른 화면에는 영향이 없다. 운영 이미지에서는 `COPY --from=repo` 가 빌드에서 먼저 실패한다. 이때 Docker 는 `repo` 를 이미지 이름으로 보고 「pull access denied」 로 끝나므로 원인을 알기 어렵다. Dockerfile 의 그 줄 위 주석에 필요한 빌드 문맥을 적는다.
- 추가 빌드 문맥은 BuildKit(buildx, compose 의 `additional_contexts`)에서만 된다.
- 한 문서를 읽다 실패하면 그 문서 자리에 「불러오지 못했어요」 를 보이고 목록은 그대로 둔다.
- INDEX 표를 읽지 못한 줄은 목록에서 빠지고 서버 로그에 남는다.

### 배포 순서

1. `fos-home-infra`: web 빌드에 추가 빌드 문맥 `repo`(저장소 root)와 빌드 인자 `SOURCE_COMMIT` 을 더한다. 옛 `web/Dockerfile` 은 쓰지 않는 문맥과 인자를 무시하므로 이 PR 보다 먼저 머지하고 배포해도 된다.
2. 이 PR 을 머지하고 배포한다. 새 Dockerfile 은 `repo` 문맥이 없으면 빌드에서 실패하므로 1 이 먼저여야 한다.

되돌릴 때는 이 PR 을 되돌리면 되고 운영 쪽 추가는 남아도 무해하다.

## 검증 전략

| 요구사항 | 경계 | 환경 |
| --- | --- | --- |
| REQ1 | 브라우저 테스트: `MEMBER` 와 `ADMIN` 모두 「고급」 의 「개발 문서」 로 연다. 로그아웃이면 로그인으로 간다 | 로컬과 CI 브라우저 |
| REQ2 | 단위 테스트: 커밋 값이 있으면 해시와 링크, 없으면 「커밋 정보 없음」 과 `main`. 브라우저 테스트: 고치는 단추가 없다 | 로컬. 실제 해시는 배포 뒤 확인 |
| REQ3 | 단위 테스트: 임시 폴더의 가짜 문서 트리로 묶음 넷, 기능 펼침, `tasks/` 제외, INDEX 순서와 상태, 보관 나눔. 단위 테스트: 찾기 거르기와 하위 맞춤의 펼침 | 로컬 |
| REQ4 | 단위 테스트: 링크 풀기(목록 안, 목록 밖, 바깥, `#절`, root 밖, 인코딩된 한글 ADR 경로와 한글 `#절`, 이미지). 브라우저 테스트: 본문의 `<script>` 가 돌지 않고 링크가 화면 안 문서로 가며, 다른 문서의 한글 `#절` 로 내려간다 | 로컬과 CI 브라우저 |
| REQ5 | 브라우저 테스트: flowchart 블록이 SVG 로 그려지고 손그림 표식이 있다. sequence 블록도 SVG 로 그려진다. 틀린 블록은 오류 문구와 원문이고 다른 블록은 그려진다 | 로컬과 CI 브라우저 |
| REQ6 | 단위 테스트: 로드맵 절 자르기와 절이 없을 때. 브라우저 테스트: 첫 화면에 로드맵 표 | 로컬 |
| REQ9 | 단위 테스트: 목록 밖 경로, `..`, 절대 경로, 인코딩된 `..` 가 404. 저장소 테스트: `web/Dockerfile` 의 `COPY --from=repo` 원본이 네 `docs` 폴더뿐이다. 로컬에서 `--build-context repo=<저장소 root>` 로 이미지를 한 번 만들어 runner 의 문서 폴더와 커밋 값을 확인한다(CI 는 web 이미지를 만들지 않는다) | 로컬과 CI |

배포 뒤 확인은 스펙의 「배포 뒤 확인」 을 따른다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 문서를 이미지에 담는 방법 | 운영 빌드가 저장소 root 를 추가 빌드 문맥 `repo` 로 주고, Dockerfile runner 단계가 네 `docs` 폴더만 복사한다. 실행 중 `fs` 로 읽는다 | 작성자 | build context 를 저장소 root 로 바꾸면 운영 빌드와 Dockerfile 의 모든 `COPY` 를 바꿔야 한다. 빌드 결과에 import 로 넣으면 서버 번들이 문서 크기만큼 커진다. [ADR-20261117 / dev-docs-image](../../../web/docs/adr/ADR-20261117-dev-docs-image.md) |
| DECISION2 | 커밋 값 | 빌드 인자 `SOURCE_COMMIT`(플러그인 이미지가 이미 쓰는 이름). 없으면 「커밋 정보 없음」 과 `main` 링크 | 작성자 | 운영 쪽 변경이 가장 작다. 로컬과 CI 빌드는 커밋을 받지 않는다 |
| DECISION3 | 본문을 그리는 부품 | 채팅 `Markdown` 을 고치지 않고 개발 문서용 부품을 따로 둔다 | 작성자 | 채팅은 모든 링크를 새 탭으로 보내고 흐르는 답의 흐림 효과가 있다. 바꾸면 채팅 화면이 함께 바뀐다 |
| DECISION4 | mermaid 를 그리는 곳 | 브라우저에서 늦게 불러 그린다 | 작성자 | 서버에서 SVG 로 그리려면 헤드리스 브라우저가 필요하다. 메모리 여유가 적은 호스트에 맞지 않는다 |
| DECISION5 | 제목 id 규칙 | `github-slugger` | 작성자 | 기존 문서의 `#절` 링크가 GitHub 규칙으로 쓰였다 |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1 | architecture | [ADR-20261117 / dev-docs-image](../../../web/docs/adr/ADR-20261117-dev-docs-image.md) | `web/Dockerfile`, 이 설계의 「문서를 담는 자리」 |
