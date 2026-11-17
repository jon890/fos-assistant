# Phase 04. 웹 이미지에 문서 담기, 로드맵 절, 브라우저 테스트와 문서 정리

**Execution profile**: deep

## 목표

운영 이미지가 배포 커밋의 문서 폴더와 커밋 값을 담고, `docs/prd.md` 에 「로드맵」 절이 생기며, 「개발 문서」 화면의 동작을 브라우저 테스트로 확인하고 책임 문서를 지금 동작에 맞춘다.

**범위 외**: 운영 빌드 설정(`fos-home-infra`, 이미 반영됨). 진행 보드(PR2).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ4, REQ5, REQ6, REQ9

**근거 문서**: `docs/features/dev-overview/design.md` 의 「문서를 담는 자리」, 「배포 순서」, 「검증 전략」, `web/docs/adr/ADR-20261117-dev-docs-image.md`

- 운영 빌드는 추가 빌드 문맥 `repo`(배포 커밋의 저장소 root)와 빌드 인자 `SOURCE_COMMIT`(40자리)을 준다. 공개 저장소이므로 운영 경로와 이름은 어디에도 적지 않는다.
- 브라우저 테스트 서버는 `web/test/browser/web-server.ts` 다. 빌드한 서버와 개발 서버 두 길에 같은 환경 변수를 넘긴다. 브라우저 테스트 규칙은 `web/test/browser/README.md` 와 [rule-60](../../../../code-rules/rule-60.md), [rule-61](../../../../code-rules/rule-61.md), [rule-62](../../../../code-rules/rule-62.md) 를 읽는다.
- `test/repo/doc-files.test.ts` 때문에 `docs/` 밖에 Markdown 파일을 둘 수 없다. 브라우저 테스트의 가짜 문서 트리는 TypeScript 문자열로 두고 서버를 띄우기 전에 임시 폴더에 쓴다.
- `web/test/browser/shell.spec.ts` 가 사이드바 링크 목록을 순서대로 확인한다(「내 브라우저」 다음에 「개발 문서」).

## 의도 메모

- 브라우저 테스트는 실제 저장소 문서가 아니라 고정한 가짜 트리를 쓴다. 실제 문서는 자주 바뀌어 테스트가 깨진다.
- CI 는 web 이미지를 만들지 않으므로 Dockerfile 의 복사 원본은 저장소 테스트로 지키고, 이미지 빌드는 인수 검증에서 로컬로 한 번 확인한다.

## 작업 항목

### 1. `web/Dockerfile`

- runner 단계에 `ARG SOURCE_COMMIT` 과 `ENV SOURCE_COMMIT=${SOURCE_COMMIT}`, `ENV DEV_DOCS_ROOT=/app/dev-docs`.
- runner 단계에 네 줄: `COPY --from=repo docs /app/dev-docs/docs`, `COPY --from=repo backend/docs /app/dev-docs/backend/docs`, `COPY --from=repo web/docs /app/dev-docs/web/docs`, `COPY --from=repo hermes/docs /app/dev-docs/hermes/docs`. `USER` 줄 앞에 둔다.
- 그 위에 주석: 추가 빌드 문맥 `repo`(저장소 root)가 필요하고 없으면 Docker 가 `repo` 이미지를 받으려다 「pull access denied」 로 끝난다는 것, 로컬은 `docker buildx build --build-context repo=.. .`(cwd: `web/`)로 만든다는 것.

### 2. `test/repo/web-dockerfile-docs.test.ts`

`web/Dockerfile` 에서 `COPY --from=repo` 줄의 원본이 정확히 `docs`, `backend/docs`, `web/docs`, `hermes/docs` 네 개이고 대상이 `/app/dev-docs/<같은 경로>` 인지 본다(REQ9). `DEV_DOCS_ROOT=/app/dev-docs` 와 `ARG SOURCE_COMMIT` 이 있는지 본다.

### 3. `web/test/browser/dev-docs-fixture.ts` 와 `web/test/browser/web-server.ts`

- fixture: `writeDevDocsFixture(dir)` 가 가짜 트리를 쓴다. flowchart mermaid 에는 `classDef warm fill:#fdecea,stroke:#c62828` 와 `class A warm` 을 넣는다. `docs/prd.md`(「로드맵」 표와 기능 링크), `docs/features/sample.md` 와 `docs/features/sample/spec.md`(한글 제목 둘, 같은 이름 제목 둘), `docs/features/other.md`(`sample.md#한글-절` 링크, 코드 파일 링크, 그림 `![구조](../images/x.png)`, 코드 펜스 밖 `<script>window.__devDocsXss = 1</script>` 와 `<img src="x" onerror="window.__devDocsXss = 2">`, `[막힌 링크](javascript:alert(1))`, flowchart mermaid, sequence mermaid, 틀린 mermaid), `docs/adr/INDEX.md` 와 한글 이름 ADR 하나, `backend/docs/adr/INDEX.md`, `docs/code-rules/INDEX.md` 와 `rule-1.md`. 테스트가 쓰는 제목과 경로를 상수로 export 한다.
- web-server.ts: 서버를 띄우기 전에 `mkdtemp` 로 임시 폴더를 만들어 fixture 를 쓰고, 두 모드 모두 `DEV_DOCS_ROOT=<그 폴더>`, `SOURCE_COMMIT=<고정 40자리 상수>` 를 넘긴다. 상수는 fixture 에서 export 한다.

### 4. `web/test/browser/dev-docs.spec.ts`

- REQ1: 기본 `page`(첫 사용자라 `ADMIN`)로 사이드바 「고급」 의 「개발 문서」 를 눌러 `/dev-docs` 에 간다. `web/test/browser/fixtures.ts` 의 `isolatedMember` fixture 와 `setSession(context, isolatedMember)`(`admin-area.spec.ts` 의 사용 예)로 `MEMBER` 세션을 만들어 `/dev-docs` 가 열리는 것도 본다. `context.clearCookies()` 뒤 `/dev-docs` 는 `/signin` 으로 간다.
- REQ2: 머리에 고정 커밋의 앞 7자리가 GitHub 커밋 링크로 보이고, 문서에 「GitHub 에서 보기」 가 같은 커밋 주소다. 「고치기」, 「편집」 단추가 없다.
- REQ3: 찾기 칸에 기능 제목 일부를 넣으면 그 줄만 남는다. 스펙 제목만 맞는 글자를 넣으면 기능 줄이 펼쳐진 채 남는다. 맞는 것이 없으면 「맞는 문서가 없어요」.
- REQ4: `other.md` 의 `<script>` 와 이벤트 속성이 돌지 않는다(`window.__devDocsXss` 가 없고 본문에 `script`, `img` 요소가 없다). 「막힌 링크」 는 링크가 아니라 글자다. `sample.md#한글-절` 링크를 누르면 화면 안 그 문서로 가고 그 제목이 화면에 들어온다. 코드 파일 링크와 그림 링크는 `target="_blank"` 이고 GitHub 주소다. 목록 밖 주소 `/dev-docs/docs/nope.md` 는 404 다.
- REQ5: flowchart 블록은 `mermaid-diagram` 안에 SVG 가 있고 손그림 표식(mermaid 의 handDrawn 출력이 남기는 rough 경로, 실제 출력을 보고 고른 선택자)이 있다. flowchart 의 `A` 노드 도형의 fill 이 `#fdecea`(또는 `rgb(253, 236, 234)`)다. sequence 블록도 SVG 다. `localStorage.theme = "dark"`(`theme.spec.ts` 의 방식)로 열어도 그림 상자의 배경색이 흰색(`rgb(255, 255, 255)`)이다. 틀린 블록 자리에 「그림을 그리지 못했어요」 와 원문이 있고 다른 두 그림은 그려져 있다.
- REQ6: `/dev-docs` 첫 화면에 fixture 로드맵 표가 보이고, 표 안 기능 링크를 누르면 그 문서로 간다.

### 5. `web/test/browser/shell.spec.ts`, `web/test/browser/admin-area.spec.ts`

- shell: 사이드바 링크 목록 기대값 끝에 `/^개발 문서/` 를 더한다.
- admin-area: 「마지막 링크까지 스크롤」 사례의 링크 이름 「내 브라우저」 를 「개발 문서」 로 바꾼다.

### 6. 책임 문서

- `docs/prd.md`: 「범위와 확인 방법」 아래(「범위 밖」 앞)에 `## 로드맵` 절을 더한다. 지금, 다음, 나중 세 열이 아니라 「단계 | 무엇 | 문서」 표로 쓰고, 기능이나 큰 작업 한 줄씩 기능 문서를 링크한다. 첫 내용은 아래에서 고르고 PR 본문에서 사용자 확인을 받는다. 「아직 만들지 않은 것」 절은 그대로 둔다.
  - 지금: 개발 문서와 로드맵(`features/dev-overview.md`), Hermes v0.23.2 업그레이드, 내장 위임이 끝나면 부모 대화의 다음 답 열기, 네이버 쇼핑 구매와 KTX 좌석 확보 개선(`features/naver-shopping.md`, `features/korail-ktx.md`)
  - 다음: 진행 보드(`features/dev-overview.md`), 실행 중 브라우저 함께 보기와 이어 가기의 남은 PR(`features/browser-cowork.md`), 실행 중 보낸 메시지를 도는 실행에 넣기(steer), Agent Runtime 추상화(Hermes 업그레이드 뒤)
  - 나중: 알림을 웹 밖으로 보내는 채널(웹 푸시), 예약 작업의 다음 단계(`features/schedule.md`)
  - 기능 문서가 없는 줄은 링크 없이 둔다. 링크는 실제 있는 파일만 건다(`test/repo/doc-links.test.ts`).
- `docs/prd.md` 「범위와 확인 방법」 표에 한 줄: 「이 저장소의 개발 문서와 로드맵을 웹에서 읽기 전용으로 본다 | 「고급」 의 「개발 문서」 에서 배포 커밋의 문서 목록과 로드맵이 보이고 mermaid 그림이 그려진다. 고치기는 PR 로 한다」.
- `web/docs/code-architecture.md`: 화면 목록에 `/dev-docs`, `/dev-docs/{저장소 경로}` 두 줄(「개발 문서. 로그인한 모든 사용자. 배포 커밋의 문서 목록과 로드맵, 본문. `docs/features/dev-overview.md`」), 사이드바 그림의 「고급」 아래 「개발 문서」, 아이콘 표에 「개발 문서 | `BookOpen`」, 「고급」 설명을 「매일 쓰지 않는 일반 화면」 으로 고치고 「개발 문서」(`/dev-docs`) 를 목록에 더한다.
- `docs/features/dev-overview.md`: 「아직 화면은 없다」 를 지우고 지금 동작을 절로 적는다(「개발 문서 화면」: 주소, 누가 여는지, 목록 묶음과 찾기, 링크 규칙, mermaid, 커밋 표시. 「이미지에 담는 방식」: ADR 링크와 운영 빌드가 줄 것). 머리에 `covers:` 줄로 `web/src/lib/dev-docs/catalog.ts`, `web/src/lib/dev-docs/links.ts`, `web/Dockerfile` 을 적는다. 진행 보드는 「아직 없다」 한 줄.
- 계획서 `docs/features/dev-overview/tasks/docs-roadmap/` 는 이 phase 에서 지우지 않는다. verify 를 마친 뒤 PR 의 마지막 커밋에서 지운다(AGENTS.md 「tasks 는 구현 문서만 담는다」).

## 검증

```bash
node --test test/repo/web-dockerfile-docs.test.ts test/repo/doc-files.test.ts test/repo/doc-links.test.ts test/repo/doc-references.test.ts
cd web && pnpm typecheck
cd web && pnpm lint
cd web && pnpm format:check
cd web && pnpm test:node
cd web && pnpm test:browser test/browser/dev-docs.spec.ts
cd web && pnpm test:browser test/browser/shell.spec.ts
cd web && pnpm test:browser test/browser/admin-area.spec.ts
scripts/dev/check-local.sh --fast
```

모두 종료 코드 0. 같은 맥에서 여러 작업자가 돌면 브라우저 테스트와 `check-local.sh` 를 그 환경의 무거운 검사 대기열로 감싼다(orch 를 쓰는 환경이면 `HEAVY_SLOTS=2 orch slot heavy -- <명령>`). 그런 도구가 없으면 그대로 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/Dockerfile` | 수정 (repo 문맥의 문서 복사, SOURCE_COMMIT, DEV_DOCS_ROOT) |
| `test/repo/web-dockerfile-docs.test.ts` | 신규 |
| `web/test/browser/dev-docs-fixture.ts` | 신규 |
| `web/test/browser/web-server.ts` | 수정 (DEV_DOCS_ROOT, SOURCE_COMMIT) |
| `web/test/browser/dev-docs.spec.ts` | 신규 |
| `web/test/browser/shell.spec.ts` | 수정 (사이드바 링크) |
| `web/test/browser/admin-area.spec.ts` | 수정 (마지막 링크 이름) |
| `docs/prd.md` | 수정 (로드맵 절, 범위 표 한 줄) |
| `web/docs/code-architecture.md` | 수정 (화면 목록, 사이드바, 아이콘, 「고급」) |
| `docs/features/dev-overview.md` | 수정 (지금 동작) |
| `docs/features/dev-overview/spec.md` | 수정 (DECISION8 근거의 mermaid 버전 표기를 뺌. 설치된 mermaid 는 12) |
| `test/repo/doc-references.test.ts` | 수정 (가짜 문서 경로를 쓰는 dev-docs 테스트 파일을 예시 파일 목록에 더함) |
