# 계획 수립 결과

## 8단계 통과 기록

| 단계 | 판정과 근거 |
| --- | --- |
| 1 구현 가능성 | 현 main에서는 가능. PR411의 실제 list/record를 호출한다. 미머지 PR422에는 UNKNOWN key 생성·후보 재사용 제외가 있다. cache가 있는 기준에서는 두 제외가 실제 main에 있어야 한다. list와 USER 정정은 이 조건에 의존하지 않는다 |
| 2 기술 스택 | 기존 Spring Boot4/Jackson3·Java·Python plugin·Node HTTP 회귀로 충분하다. 새 의존·queue·DB 없음 |
| 3 호출 흐름 | attachment 기능 절의 sequence와 빈 페이지·삭제·만료·CAS·UUID·USER 경쟁을 확정했다 |
| 4 인터페이스 | 대화 공개 UUID 아래 REST GET/USER PUT, 현재 대화만 쓰는 MCP list/MODEL record를 확정했다. UI 없음 |
| 5 API·함수 | 4단계 계약을 실제 서비스·view/input/provenance·CurrentUser·caller·session·mask 정의와 대조했다 |
| 6 데이터·구조 | 기존 불변 revision과 alias를 그대로 쓴다. 소비자와 privacy만 추가한다. cache 소유자의 기존 두 제외 구현과 main 미통합 상태를 구분한다. 저장·cache 코드는 수정하지 않는다 |
| 7 docs | 기존 기능 절과 backend/Hermes 정본을 실제 갱신했다. 새 주제 파일·계획번호 docs 참조 없음 |
| 8 task | REST와 MCP의 순차 두 phase이며 같은 관심사 PR이다. 구현 전 기본 verify_task.py의0을 요구한다 |

## 문서 영향

| 정본 | 판정 |
| --- | --- |
| docs/prd.md | 제품 목표는 바뀌지 않는다. 전체 원고·승인·임시저장 완료를 주장하지 않아 수정 없음 |
| docs/flow.md | 저장소에 없고 기능 흐름은 docs/features/attachment.md에 둔다는 규칙을 따라 새 파일 없음 |
| docs/code-architecture.md | 사용자/profile/에이전트/대화 구조는 그대로다. 상세 소비자 배치만 backend 정본에 추가 |
| backend/docs/data-schema.md | 열·타입·유일키·FK·보관·삭제는 모두 그대로다. 수정 없음 |
| 기존 storage ADR | 저장 결정은 그대로고 범위 확장은 feature의 확정 미구현 절로 설명한다. 새 ADR 없음 |
| docs/features/attachment.md | API·MCP·예산·출처 한계·오류와 권한의 계약을 추가 |
| docs/features/mcp.md | 동일 서버·등록·Memory 계약 유지와 feature 링크를 추가 |
| backend/docs/code-architecture.md | 새 소비자와 DTO 배치, privacy 책임을 추가 |
| hermes/docs/hermes-contract.md | 기존 hook에 이름만 등록하며 native 이미지·서명 계약을 유지한다는 설계를 추가 |

## 번호와 실행 경계

로컬tasks·모든worktree의tasks·root 연구 사본을 대조했고 기존 최고 번호는177이다.
git 전체 refs/이력과 원격 PR 목록도 대조한다. 코디네이터가 이 계획178과 다른 목적179를 예약했다.
plan_number.sh는 git fetch의 SSH 인증 실패로 종료2였으며 GitHub API main SHA와 실제 HEAD/main/origin/main 일치를 따로 확인했다.
마지막 점검에서 GitHub main은 새 커밋으로 진행했지만 MediaObservationService의 blob은 이 checkout과 같았다.
PR422는 여전히 미머지였다. 착수 때 전체 main을 합치는 작업과 캐시 제외 조건 검사는 구현 담당자의 책임이다.
보완 점검의 actual main은 a1df8f0이며 이 checkout과 MediaObservationService blob이 같다. analysisKey/reusable은 없다.
PR422 head 87d97b23에는 두 제외가 실제 구현되어 있다. 앞 조사 보고의 당시 상태와 현재 head의 상태를 구분한다.
PR419 head 660f4ac8도 미머지이며 기준 main을 조상으로 갖는 아카이브에서 memory_search와 stream/hook·공용 SQL probe를 읽었다.
미머지 head를 actual main producer로 취급하지 않는다. 공유 소유와 통합 후 실제 symbol 재확인을 phase에 명시했다.
이 실패를 성공으로 취급하거나 번호 검사기 우회를 하지 않는다. 번호는 코디네이터의 명시 예약을 근거로 정했다.
원본 Claude worktree와 root 연구 사본은 읽기 전용 참고이며 실제 producer·schema 정본이 아니다.
신규 구현 파일은 아직 없고 프로토타입·빈 파일로 생성 검사를 통과시키지 않았다.
착수 전에 main 변경을 반영하고 실제 시그니처와 기본 검사를 다시 확인한다.
계획만 commit/push/PR하지 않는다. 실행과 발행 담당자는 코디네이터가 배정한다.
독립 critic은 코디네이터가 별도 배정하며 planner는 하위 worker를 만들지 않는다.

## 구현 전 검사

현재 설치된 planning 스킬 번들 경로를 PLANNING_SKILL_DIR로 정한 뒤 저장소 root에서 실행한다.
phase 구현을 시작하기 전의 기본 검사이며 종료 코드 0이어야 한다.
신규 파일을 먼저 만들거나 --audit로 대체하지 않는다.

```bash
python3 "${PLANNING_SKILL_DIR}/scripts/verify_task.py" plan178-media-observation-api
node --test test/unit/feature-covers.test.ts test/unit/doc-files.test.ts
git diff --check
```

scripts/check-local.sh의 경고는 스크립트 본문과 실패 전파를 확인했다.
backend test·MySQL·web typecheck/test/build·e2e·unit·Hermes·public-safe·quality가 포함되며 첫 실패를 종료 코드 1로 전파한다.
새 기능 파일을 만들거나 covers를 첫 절 뒤로 옮기지 않았다.
독립 critic은 미확인 identity의 캐시 제외와 저장·철회 경합 한계를 우선 검토한다.
P1의 MCP controller 한정 -32700/id=null 처리와 실제 HTTP 인증·로그 회귀를 확정했다.
P2의 detail/text 수집 전 null, cache head/main 구분, observation=null 우선 assurance 규칙을 기존 정본과 두 phase에 반영했다.
운영 코드 실제 변경이 1,500줄을 넘으면 REST/MCP가 각각 기능·회귀·해당 설계를 갖춘 PR로 나눈다. tests/docs는 별도 집계한다.
