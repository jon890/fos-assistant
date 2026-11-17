# 개발 문서와 로드맵(PR1) 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 브라우저 테스트 fixture 트리, 로그인한 테스트 계정 | `web/test/browser/dev-docs.spec.ts` 의 REQ1 사례와 `web/test/browser/shell.spec.ts` 를 돌린다 | `ADMIN` 과 `MEMBER` 모두 사이드바 「고급」 의 「개발 문서」 로 `/dev-docs` 가 열린다. 로그인하지 않으면 로그인 화면으로 간다 | 로컬 브라우저 테스트(빌드한 서버) | worker | 없음(phase 4 구현 뒤) | PR 전 |
| REQ2 | fixture 와 고정 `SOURCE_COMMIT` | `dev-docs.spec.ts` 의 REQ2 사례, `web/test/unit/dev-docs-source.test.ts` | 앞 7자리 커밋 링크와 같은 커밋의 「GitHub 에서 보기」, 고치는 단추 없음. 값이 틀리거나 없으면 「커밋 정보 없음」 과 `main` | 로컬 단위·브라우저 테스트 | worker | 없음(phase 4 구현 뒤) | PR 전 |
| REQ2 | 배포된 운영 웹 | 관리자가 아닌 계정과 관리자 계정으로 「개발 문서」 를 열어 머리의 해시를 배포 커밋과 비교한다 | 해시가 배포 커밋과 같고 두 계정 모두 열린다 | 운영 | 코디네이터 | 배포(이 PR 머지 뒤) | PR 뒤 |
| REQ3 | 단위 테스트의 가짜 트리, 브라우저 fixture | `web/test/unit/dev-docs-catalog.test.ts`, `dev-docs.spec.ts` 의 REQ3 사례 | 묶음 넷과 결정 나눔, INDEX 첫 줄, 보관, `tasks/` 제외. 찾기가 제목과 파일 이름에 맞고 자식만 맞으면 펼쳐진다 | 로컬 | worker | 없음(phase 1, 4 구현 뒤) | PR 전 |
| REQ4 | 가짜 트리와 fixture 의 `other.md` | `web/test/unit/dev-docs-links.test.ts`, `dev-docs-read.test.ts`, `dev-docs.spec.ts` 의 REQ4 사례 | `<script>` 가 돌지 않는다. 한글 `#절` 로 화면 안 이동, 목록 밖과 그림은 같은 커밋 GitHub 새 탭, `javascript:` 는 링크가 아니다. 목록 밖 주소는 404 | 로컬 | worker | 없음(phase 1, 4 구현 뒤) | PR 전 |
| REQ5 | fixture 의 flowchart, sequence, 틀린 mermaid | `dev-docs.spec.ts` 의 REQ5 사례 | flowchart 는 손그림 SVG 이고 `classDef` 의 fill 이 노드에 보인다. sequence 는 기본 모양 SVG. 어두운 테마에서도 그림 상자는 흰 바탕. 틀린 블록만 「그림을 그리지 못했어요」 와 원문 | 로컬 브라우저 테스트 | worker | 없음(phase 3, 4 구현 뒤) | PR 전 |
| REQ5 | 배포된 운영 웹 | `docs/features/naver-shopping/design.md` 를 「개발 문서」 에서 연다 | 손그림 flowchart 가 노드 색과 함께 그려진다 | 운영 | 코디네이터 | 배포(이 PR 머지 뒤) | PR 뒤 |
| REQ6 | fixture `docs/prd.md` 와 실제 `docs/prd.md` | `dev-docs-read.test.ts` 의 로드맵 사례, `dev-docs.spec.ts` 의 REQ6 사례, 실제 `docs/prd.md` 에 `## 로드맵` 이 있는지 `grep -n '^## 로드맵' docs/prd.md` | 첫 화면에 로드맵 표, 표 안 링크가 그 문서로 간다. 실제 prd 에 절이 있다 | 로컬 | worker | 없음(phase 4 구현 뒤) | PR 전 |
| REQ9 | `web/Dockerfile` | `test/repo/web-dockerfile-docs.test.ts`. 그리고 `cd web && docker buildx build --build-context repo=.. --build-arg SOURCE_COMMIT=$(git rev-parse HEAD) --target runner -t dev-docs-check --load .` 뒤 `docker run --rm --entrypoint sh dev-docs-check -c 'ls /app/dev-docs; echo $SOURCE_COMMIT $DEV_DOCS_ROOT'` | 복사 원본이 네 `docs` 폴더뿐이다. 이미지 안 `/app/dev-docs` 에 `docs backend web hermes` 만 있고 커밋과 문서 위치 값이 들어 있다 | 로컬 Docker | worker | 없음(phase 4 구현 뒤) | PR 전 |
