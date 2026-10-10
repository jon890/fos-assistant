# 완료 결과 cache 구현 전달

## 결과와 선행 조건

PR411의 저장 producer를 사용하며 현재 최신 MODEL 완료 결과에 새 UUID alias만 추가한다.
구현 전 검증은 저장 producer의 존재와 기본 verify를 확인하며 cache 기능 성공을 뜻하지 않는다.

## 진행과 수락

독립 critic은 현재 producer 정의와 설계·phase를 대조하여 blocker를 보고한다.
작성자가 지적을 반영하고 코디네이터가 결과를 확인한 뒤 별도 제품 executor를 배정한다.
그 전에 코디네이터가 설계와 계획을 로컬 checkpoint로 커밋하여 새 ADR을 Git 추적 상태로 만든다.
checkpoint를 같은 최종 구현 브랜치의 이력으로 전달하며 계획만 PR을 만들지 않는다.
제품 executor는 최신 main을 일반 merge한 head에서 기본 verify 종료 0을 다시 확인한다.
새 ADR의 `git ls-files --error-unmatch` 종료 0도 먼저 확인한다.
현재 계획 작성 worker는 docs/tasks만 수정하며 구현·commit·push·PR·하위워커를 실행하지 않는다.

| 수락 항목 | 필요한 증거 |
| --- | --- |
| producer | PR411 main 머지와 실제 저장·alias·본문 정의 일치 |
| 기본 검사 | 구현 전 verify_task.py 종료 0. audit는 대체 불가 |
| hash | 고정 독립 vector, 각 조건 변경과 기존 UUID hash 호환 |
| 재사용 | 기존 관찰·시각 유지, 같은 TX의 새 alias와 재시작 재시도 |
| 안전 경계 | CAS/USER 우선, SQL 전파, 원본 스트림 읽기·닫기 뒤 최신 SQL 접근 재확인과 현재 시계 만료 검사 |
| 평문 호환 | cache만 body_key_id 비null 필수. 목록과 과거 UUID는 기존 호환 유지 |
| 경합 | cache alias 두 성공과 실제 새 revision 한 승자/한409 |
| 저장 | H2와 실제 MySQL의 nullable key·UNIQUE·복합 FK·색인·cascade |
| 기존 회귀 | crypto, 저장, 첨부 삭제·만료와 대화 purge 유지 |
| 품질 | checkpoint 뒤 ADR 수정 M과 신규 제품 파일 A 등 staged 종류·범위 일치, qualityCheck와 check-local 종료 0 |
| 문서 | 구현 전 상태 정리, 한국어 검사와 의미 점검, 공개 정보 검사 |

## PR와 범위

단일 작동 구현, 설계, DDL과 회귀를 함께 Draft PR로 올린다.
계획만으로 PR을 열지 않는다. 계획서는 최종 구현 PR에서 삭제한다.
phase는 관심사 커밋이며 PR과 일대일 관계를 강제하지 않는다.
PR414가 실제 main에 머지되어 `scripts/pr-size.mjs` 상한은 운영 코드 변경 1,500줄이다.
수치와 제외 규칙의 정본은 해당 스크립트이며 제품 executor는 최신 main을 합친 뒤 그 값을 확인한다.
예외 판정과 라벨은 코디네이터만 처리하고 executor는 붙이지 않는다.
상한 초과 시 임의로 cache의 TX 계약을 나누지 않고 작동하는 구현 경계를 코디네이터에게 보고한다.

외부 API/MCP/UI, 원고·approval·job, provider 분석, 운영·실계정, Hermes core와 새 의존성은 범위 밖이다.
원본 Claude 작업, 다른 저장소와 루트의 사용자 작업 16개 파일을 수정하지 않는다.
scanner 후보 조회 P3는 별도 관심사다.
배포나 외부 실행을 수락 조건으로 넣지 않으므로 remote-verification 파일은 만들지 않는다.
cache 성공을 실제 모델 성공, 새 API나 화면 완료로 보고하지 않는다.
