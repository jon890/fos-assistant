# plan68. Gmail 커넥터

phase 는 번호 순서로 한다. 뒤 phase 가 앞 phase 에 기댄다.

| phase | 무엇 | 기대는 것 |
| --- | --- | --- |
| 01 | 도구 선언의 `grant` 를 대시보드 plugin, Control Plane, 웹이 읽는다 | 없음 |
| 02 | `hermes/connectors/gmail/` 과 그 검사 | 01. `connector.json` 이 `"grant": false` 를 쓴다 |
| 03 | 공통 계약 검사와 소유자 | 02. 검사할 커넥터가 있어야 한다 |

결정과 계약은 `docs/` 에 이미 있다. phase 는 문서를 고치지 않는다. 구현이 문서와 달라져야 하면 멈추고 알린다.
