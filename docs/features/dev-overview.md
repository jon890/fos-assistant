# 개발 현황

사용자가 assistant 웹에서 이 저장소의 개발 문서와 로드맵을 보며 큰 그림을 점검하는 기능이다.

covers: `web/src/lib/dev-docs/catalog.ts`, `web/src/lib/dev-docs/links.ts`, `web/Dockerfile`

요구와 결정은 스펙 [`dev-overview/spec.md`](dev-overview/spec.md), 구조는 설계 [`dev-overview/design.md`](dev-overview/design.md)가 갖는다.
이 파일은 지금 동작하는 것만 적는다. 진행 보드는 웹에 연결하지 않는다(스펙 DECISION18).
로드맵(`docs/prd.md` 의 「로드맵」 절)은 코디네이터가 큰 작업이 끝나거나 새로 생길 때 PR 로 갱신한다.

## 개발 문서 화면

로그인한 사용자는 역할과 상관없이 사이드바 「고급」 의 「개발 문서」(`/dev-docs`)를 연다. 로그인하지 않았으면 로그인 화면으로 간다.
첫 화면은 [`docs/prd.md`](../prd.md) 의 「로드맵」 절이고, 옆(좁은 화면은 위)에 문서 목록이 있다. 머리에 그 문서를 담은 커밋의 앞 7자리가 GitHub 커밋 링크로 보인다. 고치는 단추는 없고 고치기는 PR 로 한다.

| 묶음 | 담는 것 |
| --- | --- |
| 제품 | `docs/prd.md`, `docs/code-architecture.md`, 모듈의 `code-architecture.md`, `backend/docs/data-schema.md`, `hermes/docs/hermes-contract.md` |
| 기능 | `docs/features/*.md` 마다 한 줄. 기능 폴더가 있으면 펼쳐 스펙과 설계를 보인다. 구현 계획 파일은 넣지 않는다 |
| 결정 | 공통, backend, web, hermes 로 나눈다. 나눔마다 그 `INDEX.md` 가 첫 줄이고, 표의 순서와 상태 칸의 첫 문장을 따른다. 보관된 ADR 은 「보관」 에 둔다 |
| 코드 규칙 | `docs/code-rules/INDEX.md` 다음 `rule-N.md` 번호 순 |

- 목록 이름은 문서의 첫 제목이고, ADR 줄은 그 `INDEX.md` 표의 제목 칸(백틱과 링크를 벗긴 글)이다. 찾기 칸은 목록 이름과 확장자를 뺀 파일 이름을 맞추고, 스펙과 설계 줄은 목록 이름만 맞춘다. 스펙이나 설계만 맞으면 그 기능 줄이 펼쳐진 채 남는다.
- 목록에 든 문서만 `/dev-docs/<저장소 경로>` 로 열린다. 나머지 경로는 404 다.
- 본문은 react-markdown 의 기본값으로 그려 문서 안의 HTML 이 돌지 않는다. 목록 안 문서를 가리키는 링크는 화면 안으로 가고 `#절` 로 내려간다. 목록 밖 파일(코드, README, 그림)은 같은 커밋의 GitHub 파일을 새 탭으로 연다. 그림은 그리지 않고 「그림: <이름>」 링크로 바꾼다.
- mermaid 블록은 그 문서를 열 때만 mermaid 를 불러 그린다. 손그림을 지원하는 그림 종류(flowchart, state 등)는 손그림과 `classDef` 색, sequence 는 기본 모양이다. 어두운 테마에서도 흰 바탕에 그린다. 문법이 틀린 블록만 원문과 「그림을 그리지 못했어요」 가 보인다.

`web/test/unit/dev-docs-*.test.ts` 와 `web/test/browser/dev-docs.spec.ts` 가 확인한다.

## 이미지에 담는 방식

웹 이미지가 배포 커밋의 `docs/`, `backend/docs/`, `web/docs/`, `hermes/docs/` 를 `/app/dev-docs/` 에 복사하고 화면이 실행 중에 읽는다. 근거는 [ADR-20261117 / dev-docs-image](../../web/docs/adr/ADR-20261117-dev-docs-image.md)에 있다.
운영 빌드는 추가 빌드 문맥 `repo`(배포 커밋의 저장소 root)와 빌드 인자 `SOURCE_COMMIT`(40자리)을 줘야 한다. 문서 위치는 `DEV_DOCS_ROOT`, 커밋은 `SOURCE_COMMIT` 환경 변수로 읽고, 커밋이 40자리 소문자 16진수가 아니면 「커밋 정보 없음」 을 보인다.
`test/repo/web-dockerfile-docs.test.ts` 가 복사 원본이 네 `docs` 폴더뿐인지 지킨다.
