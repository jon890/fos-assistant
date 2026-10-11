## ADR-20261011 / content-snapshot-compiler: 원고 compiler는 별도 private process로 호출한다

- **Date**: 2026-10-11
- **status**: `accepted`
- **결정**: 서비스 Bearer로 인증한 snapshot HTTP 요청만 별도 compile process를 띄운다. 공개 MCP 서버는 private 도구를 등록하지 않는다. 선언은 protocol·세 도구 이름·strict capabilities의 다섯 키이며 이번 구현은 compile만 지원한다.
- **맥락**: 기존 compiler는 불변 원고와 원본 참조를 DSL·manifest·두 해시로 바꾸지만 호출 경계가 없었다. 모델에게 내부 경로를 주거나 기존 execute로 private 도구를 호출하면 공개 설치와 내부 본문 transport의 경계가 흐려진다.
- **대안 기각**: 모델 도구 인자나 profile env로 private mode를 켜지 않는다. 일반 execute에 private 이름을 넣거나 금융 manifest를 재사용하지 않는다.
- **결과**: 기존 순수 compiler와 저장 동작을 유지하며 입력·출력 바이트와 5초 deadline을 검사한다. 선언과 실제 private tools/list가 다르면 호출하지 않고 start/status는 자식 실행 전 거절한다.
- **한계**: DB, 최초 원고 MCP 생성, 승인, worker start/status와 실제 사진 identity는 구현하지 않았다. 본문 없는 진단 정책의 실제 구현·운영 수락 전에는 본문 transport 운영 활성화를 금지한다.

입력·상한·카탈로그와 오류 계약은 [원고 기능](../../../docs/features/content-draft.md#compile-전용-내부-transport)이 갖는다.
