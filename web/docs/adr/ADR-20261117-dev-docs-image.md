## ADR-20261117 / dev-docs-image: 개발 문서는 배포 커밋의 문서 폴더를 웹 이미지에 복사해 읽기 전용으로 보인다

- **status**: `accepted`
- Date: 2026-11-17
- **decision-type**: `architecture`
- **areas**: `operations`
- **현재 규칙의 원본**: 설계 [`docs/features/dev-overview/design.md`](../../../docs/features/dev-overview/design.md) 의 「문서를 담는 자리」 와 「배포 순서」, 스펙 [`spec.md`](../../../docs/features/dev-overview/spec.md) 의 DECISION1, DECISION10, `web/Dockerfile`
- **결정**: 웹의 「개발 문서」 화면은 웹 이미지를 만든 커밋의 문서를 보인다. 운영 빌드가 저장소 root 를 추가 빌드 문맥 `repo` 로, 배포 커밋을 빌드 인자 `SOURCE_COMMIT` 으로 주고, `web/Dockerfile` 의 runner 단계가 루트와 모듈의 `docs/` 폴더만 복사한다. 화면은 실행 중에 그 폴더를 파일로 읽고, 목록에 든 문서만 연다.
- **맥락**: 사용자는 설계 그림과 검토 문서를 저장소 `docs/` 하나에 두고 웹에서 읽기 전용으로 보기로 했다(스펙 DECISION1). 웹 이미지는 `web/` 폴더만 빌드 문맥으로 받아 `docs/` 가 빌드에 들어오지 않는다. Next.js `standalone` 출력은 코드가 import 하지 않는 파일을 담지 않는다. 운영 빌드는 배포 커밋으로 받은 전체 clone 에서 돌고 커밋 값을 안다.
- **대안 기각**:
  - 실행 중 GitHub 에서 읽는다: 웹에 토큰과 바깥 호출이 생기고, GitHub 장애나 호출 한도가 화면을 막는다. 배포한 코드와 보이는 문서의 커밋이 어긋날 수 있다
  - build context 를 저장소 root 로 바꾼다: Dockerfile 의 모든 `COPY` 와 운영 빌드 설정을 함께 바꿔야 하고, 의존성 설치 층의 캐시 기준도 바뀐다
  - 빌드 때 문서를 JSON 으로 묶어 import 한다: 서버 번들과 메모리가 문서 크기만큼 커진다. 메모리 여유가 적은 호스트에 맞지 않는다
  - 문서 사본을 `web/` 아래에 커밋한다: 원본이 둘이 되고 어긋난다
- **결과**:
  - 얻는 것: 보이는 문서가 배포한 코드와 같은 커밋이다. 바깥 호출과 토큰이 없다. 고치기는 지금처럼 PR 이다
  - 감당할 것: 운영 빌드가 `repo` 문맥을 주지 않으면 새 Dockerfile 이 빌드에서 실패하므로 운영 쪽 변경을 먼저 배포해야 한다. `docker build web` 만으로는 이미지가 만들어지지 않고 BuildKit 의 `--build-context repo=<저장소 root>` 가 필요하다. 빠뜨리면 Docker 가 `repo` 를 이미지 이름으로 보고 「pull access denied」 로 끝나 원인이 바로 보이지 않는다. CI 는 web 이미지를 만들지 않으므로 Dockerfile 의 복사 원본은 저장소 테스트로 지킨다. 문서를 고쳐도 웹을 다시 배포해야 화면에 보인다
  - 권한: `repo` 문맥의 checkout 권한이 좁아도(디렉터리 700, 파일 600) 화면 서버 사용자가 읽도록 네 `COPY` 에 `--chmod=a+rX` 를 준다. 소유자는 root 로 두어 화면 서버가 문서를 고치지 못한다
- **확인 방법**: `web/test/unit/dev-docs-*.test.ts`(목록, 경로 대조, 링크 풀기), Dockerfile 복사 원본과, 좁은 권한의 문맥을 복사해도 nextjs 가 읽는지 docker 로 보는 저장소 테스트, `web/test/browser/dev-docs.spec.ts`, 배포 뒤 화면 위 커밋 해시가 배포 커밋과 같은지
