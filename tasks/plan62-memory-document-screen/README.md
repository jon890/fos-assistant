# plan62 문서와 서비스 토큰 화면

`/memory` 화면에 문서를 쓰고 고치는 절과 서비스 토큰을 발급하고 폐기하는 절을 더한다. web 과 브라우저 검사만 바꾼다.
결정과 계약은 아래 두 문서에 있다.

- `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md`
- `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md`

**plan61-memory-document-service-api 가 main 에 머지된 뒤에 시작한다.**
`backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentController.java` 와 `ServiceTokenController.java` 가 없으면 `PHASE_BLOCKED: plan61 이 머지되지 않았다` 를 출력하고 멈춘다.

## PR 과 계획서 삭제

- plan 하나를 PR 하나로 올린다. 그 plan 의 계획서 디렉터리는 그 PR 의 마감 단계에서 지운다. phase 는 `tasks/` 를 바꾸지 않는다
- 이 계획서와 ADR-056, ADR-057, ADR-058 은 구현보다 먼저 main 에 들어와 있다. 서로 기대는 구현 PR 이 여럿이라 공통 계약을 먼저 정했다(루트 `AGENTS.md` 의 「머지는 PR 로 한다」 가 그 예외를 정한다). 그 ADR 의 `status` 와 `docs/adr/INDEX.md` 는 「아직 구현 전이다」 로 적혀 있고, 구현한 plan 의 마지막 phase 가 그 글을 지운다
- 순서는 문서 API 와 서비스 토큰(plan61), 화면(plan62), 기존 지식 이관(plan63)이다. 앞의 것이 main 에 머지된 뒤에 다음 것을 시작한다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다
- **민감 정보의 실제 값을 검사와 문서에 적지 않는다.** 문서 본문은 `평문-표식-7391` 같은 지어낸 글을 쓴다
- 브라우저는 Control Plane 을 직접 부르지 않는다. `web/src/app/api/` 아래 서버 라우트를 거친다(`web/AGENTS.md`)
- 화면 문구는 해요체로 쓴다. 색과 간격은 테마 토큰을 쓴다. 규칙은 `web/AGENTS.md` 의 「화면 문구」 와 「색과 간격은 테마 토큰이 소유한다」 가 갖는다
- 화면 부품은 `web/src/components/ui/` 에 있는 것을 쓴다. 새 부품을 만들지 않는다
- 서비스 토큰의 원문을 브라우저 저장소(`localStorage`, `sessionStorage`)와 주소에 두지 않는다. 발급 응답을 받은 화면의 상태에만 둔다
- 기능 변경과 포맷을 한 커밋에 섞지 않는다. 포맷이 필요하면 `scripts/quality.sh fix` 가 바꾼 것을 따로 커밋한다
- 로컬에서는 이 plan 이 더하거나 고친 spec 만 돌린다. 전체 브라우저 검사는 PR 의 CI 가 돌린다(루트 `AGENTS.md` 의 「확인」)

## 화면에서 쓰는 말

| 안에서 부르는 이름 | 화면에서 쓰는 말 |
| --- | --- |
| `DOCUMENT` | 문서 |
| collection | 영역. 이름은 `displayName` 을 보인다 |
| `documentKey` | 문서 이름 |
| `SENSITIVE` | 민감한 내용 |
| `revision` | N번째 판 |
| 서비스 토큰 | 외부 서비스 연결 토큰. 절 제목은 「외부 서비스 연결」 |

## 범위 밖

- collection 탭, 판 이력 화면, 출처 표시
- 그룹 공용 문서, 제목 고치기
- 관리자가 에이전트의 collection 과 민감 허용을 고치는 화면
- 기존 지식 저장소의 묶음을 올리는 「가져오기」 절. plan63 이 만든다(ADR-058)
- 만료 없는 토큰. 만들지 못한다(ADR-056)
- 민감한 `MEMORY` 항목을 「나에 대해 아는 것」 절에서 고치는 일. backend 가 `MEMORY_SENSITIVE_NOT_EDITABLE` 로 거절한다(ADR-055). 화면은 그 문구를 보이는 것으로 충분하다
