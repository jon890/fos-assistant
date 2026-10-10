# 구현 전달과 PR 분할

새로 시작하는 lane은 선행 머지를 확인하고 최신 main을 fetch한 뒤 그 main에서 브랜치를 만든다.
기존 작업 브랜치는 최신 main을 fetch하고 일반 merge로 합친다. rebase와 force push로 이력을 다시 쓰지 않는다.
합친 head에서 해당 phase의 로컬 검사와 CI를 다시 확인하고 일반 push로 전달한다.

선행 조건: A/C와 병렬이다. 정적 문서만 공유하며 A의 미구현 파일을 import하거나 수정하지 않는다.

이 plan은 한 관심사 PR로 전달하며 내부 phase는 순서대로 검증한다.
PR마다 운영 변경줄은 scripts/pr-size.mjs 기준으로 1,000 이하이어야 한다.
구현 없이 문서만 PR로 올리지 않는다.
phase 1만 전달할 때는 해당 구현과 해당 설계 절만 담는다.
완료한 phase 파일은 그 구현 PR에서 지우고 index.json은 남은 phase만 실행하도록 갱신한다.
마지막 phase의 구현 PR에서 남은 plan 디렉터리를 지운다.
다른 미구현 plan 디렉터리는 어느 구현 PR에도 섞지 않는다.
코디네이터가 설계 파일의 해당 절과 ADR을 골라 통합하고 구현 워커는 다른 단계 설계 내용을 일괄 stage하지 않는다.
독립 A/B/C는 다른 lane의 파일과 shared docs를 수정하지 않는다.

snapshot은 canonicalBlocks를 포함한 전체 해시 입력을 보존하고 Java/TS golden vector 계약을 함께 전달한다.

A/B/C 구현은 격리된 워크트리에서 진행한다. 같은 작업 공간을 쓴다면 backend·web 빌드 산출물을 공유하는 quality/check-local/MySQL 검증은 순차 실행한다.
