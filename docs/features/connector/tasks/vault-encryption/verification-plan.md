# 커넥터 보관 파일 암호화 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 합성 key 파일과 두 환경 변수, 시험 플러그인 | `PUT /api/connector-vault` 로 칸 값을 쓰고 파일을 읽는다 | 파일이 v2 이고 파일 글에 칸 값이 없다 | Hermes 없는 plugin 단위 테스트 | 없음 | PR 전 |
| REQ1 | 위 v2 보관 파일 | 같은 vault 로 `call`, `tools` 를 부르고 자식 env 를 본다. 같은 vault 로 바인딩을 설치하고 profile `.env` 를 읽는다 | 자식 env 와 `.env` 에 원래 값이 있다 | 같음 | 없음 | PR 전 |
| REQ1 | v2 보관 파일 | 기동 이관을 다시 돌린 뒤 같은 vault 로 `call` 을 부른다 | 원래 값을 받고 파일은 바뀌지 않는다 | 같음 | 없음 | PR 전 |
| REQ2 | 연결 c1 의 v2 파일 | 파일을 c2 로 복사해 읽는다. c1 파일의 `connector` 값만 다른 플러그인으로 고쳐 읽는다 | 둘 다 읽기 실패. `call` 은 `unavailable`, 예외 메시지와 로그에 칸 값이 없다 | 같음 | 없음 | PR 전 |
| REQ3 | v1 파일, 옛 key 의 v2 파일, JSON 이 깨진 파일, 다른 연결에서 복사한 v2 파일, key 파일에 없는 key 의 v2 파일 | 기동 이관을 돌리고 파일과 로그를 본다 | v1 과 옛 key 파일은 활성 key 의 v2 가 되고 값이 같다. 나머지는 바이트가 그대로다. 로그에 수와 남은 이름만 있다. 다시 돌리면 바뀌는 파일이 없다 | 같음 | 없음 | PR 전 |
| REQ3 | key 없음 | 기동 이관을 돌린다 | 파일이 바뀌지 않고 평문 파일 수가 로그에 남는다 | 같음 | 없음 | PR 전 |
| REQ3 | 「key 필수」 를 켜고 key 없음 | `register` 를 부른다 | 기동이 멈추지 않고 key 가 준비되지 않았다는 error 로그가 남는다 | 같음 | 없음 | PR 전 |
| REQ4 | 「key 필수」 를 켜고 key 없음, 하나만 있음, 파일 모양 틀림, 활성 id 없음 | `PUT` 을 부른다 | 503 이고 파일이 바뀌지 않으며 응답과 로그에 값이 없다 | 같음 | 없음 | PR 전 |
| REQ4 | 「key 필수」 를 켜고 key 없음, v1 보관 파일 | 같은 vault 로 `call` 을 부른다 | 원래 값으로 돈다 | 같음 | 없음 | PR 전 |
| REQ4 | 「key 필수」 를 끄고 key 없음 | `PUT` 을 부른다 | 200, v1 파일, 평문으로 썼다는 경고 로그 | 같음 | 없음 | PR 전 |
| REQ4 | Control Plane 의 연결 등록 | `putVault` 가 실패하면 연결을 `PENDING` 으로 남기는 코드를 읽는다 | `ConnectorConnectionService` 의 보관 파일 단계가 그렇게 한다(코드 변경 없음) | 코드 읽기 | 없음 | PR 전 |
| REQ5 | 바뀐 문서 | 공개 정보 검사를 돌리고 diff 를 읽는다 | key 파일 자리, 컨테이너 이름, key 를 만드는 명령이 없다. 「다시 등록」 과 「Control Plane KEK 와 다른 key」 가 있다 | 작업 공간 | 없음 | PR 전 |
| REQ3 | 운영 배포 | 코디네이터가 `fos-home-infra` 의 절차로 기동 로그를 본다 | 평문 파일 0, 옛 key 파일 0 | 운영 | 배포 (운영 key 파일과 세 환경 변수를 넣은 뒤) | PR 뒤 |
| REQ1 | 운영 배포 | 연결을 붙인 에이전트로 도구를 한 번 부르고, 연결 확인을 한 번 누른다 | 둘 다 성공 | 운영 | 배포 (위 행을 확인한 뒤) | PR 뒤 |

개발 테스트는 `hermes/tests/test_connector_vault_encryption.py` 와 `hermes/tests/test_connector_vault_migration.py` 다.
