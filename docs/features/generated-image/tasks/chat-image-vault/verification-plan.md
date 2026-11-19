# 비서가 만든 이미지 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 도는 실행이 있는 대화, 테스트용 PNG | `GeneratedImageEndpointTest` 로 서명한 올리기를 보내고 `GeneratedImageTurnTest` 로 turn 을 끝낸 뒤 메시지 응답을 읽는다 | 그 답의 결과물에 `kind` 가 `IMAGE` 인 항목이 있고 파일 주소가 원본 바이트를 준다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ1 | 캐시 폴더의 테스트용 그림, 가짜 Control Plane | `hermes/tests/test_generated_image.py` 로 결과 hook 을 부른다 | 모델에게 가는 결과에 경로와 `MEDIA:` 가 없다 | Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 부모 turn 이 끝난 뒤 맡긴 실행이 올린 그림 | `GeneratedImageTurnTest` 로 다음 turn 을 끝낸다 | 그림이 다음 답에 묶인다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ2 | 그림 두 장이 묶인 답 | `web/test/browser/generated-image.spec.ts` | 두 장이 만든 순서로 보인다 | 브라우저 테스트(응답 대역) | worker | 없음 | PR 전 |
| REQ3 | 그림이 묶인 답 | 같은 spec 을 넓은 화면과 휴대폰 폭으로 돈다 | 그림을 누르면 크게 보기가 열리고 「내려받기」 의 파일 이름이 제목이다 | 브라우저 테스트(응답 대역) | worker | 없음 | PR 전 |
| REQ3 | 올린 PNG | `GeneratedImageEndpointTest` 가 파일 주소로 받은 바이트를 올린 바이트와 비교한다 | 바이트가 같다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ4 | 없음 | 실행 입력의 단락을 대조하는 테스트(`ArtifactTest`)를 돈다 | 단락에 자세한 지시문 규칙과 `제목:` 줄 규칙이 있다 | Control Plane 테스트 | worker | 없음 | PR 전 |
| REQ4 | `제목:` 줄이 든 지시문 | `test_generated_image.py` 로 `pre_tool_call` 을 부른다 | 이미지 도구에 가는 지시문에서 제목 줄이 빠진다 | Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ5 | 지시문과 함께 올린 그림 | `GeneratedImageTurnTest` 로 응답의 `prompt` 를 읽고, 브라우저 spec 으로 접힌 칸을 편다 | 응답의 지시문이 올린 글과 같고, 화면에서는 누르기 전에 보이지 않는다 | Control Plane 통합 테스트, 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ6 | 같은 제목의 올리기 두 번, 제목 없는 올리기 | `GeneratedImageEndpointTest` | 둘째 제목이 `<제목> 2` 이고 제목이 없으면 날짜와 시각이다. `%` 와 `;` 는 제목에서 빠진다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ7 | 자동 보관을 켠 사용자와 끈 사용자 | `GeneratedImageTurnTest` 로 완료한 turn 뒤 보관함을 읽는다 | 켠 사용자는 보관본이 있고 바이트가 원본과 같다. 끈 사용자는 없고 직접 보관 뒤에 있다. 대화를 정리해도 남는다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ7 | 보관본이 없는 그림이 묶인 답 | 브라우저 spec 으로 크게 보기를 연다 | 「보관」 단추가 보이고 누르면 보관 요청이 나간다 | 브라우저 테스트(응답 대역) | worker | 없음 | PR 전 |
| REQ8 | 그림 보관본이 든 보관함 응답 | 브라우저 spec 으로 「보관한 결과물」 을 연다 | 그 줄에 제목과 작은 그림이 보인다 | 브라우저 테스트(응답 대역) | worker | 없음 | PR 전 |
| REQ9 | 파일을 지운 그림 행과 그 보관본 | `GeneratedImageTurnTest` 로 응답의 `vaultItemId` 를 읽고, 브라우저 spec 으로 링크를 본다 | 보관본이 있으면 번호와 링크가 있고, 보관본을 지우면 없다. 지시문은 남는다 | Control Plane 통합 테스트, 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ10 | 다른 사용자, 끝난 실행 | `GeneratedImageEndpointTest` | 다른 사용자는 그 파일 주소에서 없는 대화와 같은 응답을 받고, 끝난 실행의 올리기는 거절된다. 어느 실패도 404 가 아니다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ10 | 실패한 이미지 도구 결과, 올리기 실패 | `test_generated_image.py` | 실패 결과는 그대로 가고, 올리기 실패는 경로 없는 오류 글로 바뀐다 | Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 중지한 답, 답 없이 끝난 turn | `GeneratedImageTurnTest` | 중지한 답에 그때까지의 그림이 묶이고, 답 없는 turn 의 그림은 다음 답에 묶인다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ7 | 중지한 답의 그림, 5MiB 를 넘는 그림 | `GeneratedImageTurnTest` | 중지한 답은 자동 보관하지 않고, 상한을 넘는 그림은 `FAILED` 로 남는다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ8 | 그림 보관본이 든 보관함 응답 | 브라우저 spec 으로 그 줄의 「미리보기」 를 누른다 | 그림 전체가 보인다 | 브라우저 테스트(응답 대역) | worker | 없음 | PR 전 |
| REQ9 | 보관 기간이 지난 대화 폴더의 그림 | `GeneratedImageTurnTest` 가 `ArtifactCleaner.cleanExpired` 를 돈다 | 그림 행에 `deleted_at` 이 적히고 지시문이 남는다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ10 | 다른 사용자 | `GeneratedImageEndpointTest` 가 다른 사용자로 그 대화의 메시지를 읽는다 | 없는 대화와 같은 응답이라 지시문을 읽지 못한다 | Control Plane 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ4 | 가짜 Hermes 가 자세한 지시문으로 그림을 올리는 시나리오 | `test/e2e` 의 그림 시나리오를 돈다 | fos-ctx 와 같은 모양의 multipart 를 Control Plane 이 받고, 화면에 보이는 지시문이 사용자의 부탁 글과 다르고 더 길다 | e2e(가짜 Hermes) | worker | 없음 | PR 전 |
| REQ3 | 이미지 서비스가 설정된 관리자 비서, 휴대폰 | 운영에서 그림을 크게 보고 두 손가락으로 확대한다 | 확대된다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
| REQ1 | 이미지 서비스가 설정된 관리자 비서 | 운영에서 그림 한 장을 부탁한다 | 답에 그림이 보이고 경로 글자가 없으며 보관함에 남는다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
