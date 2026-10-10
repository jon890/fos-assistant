## ADR-20261010 / media-observation-storage: 관찰은 암호화한 불변 revision과 영속 요청 alias로 저장하고 첨부와 함께 지운다

- **status**: `accepted`
- Date: 2026-10-10

### 맥락

같은 사진의 모델 관찰과 사용자 정정을 구분하고, 응답이 끊긴 요청도 원래 저장 결과로 재시도할 수 있어야 한다.
사진이 지워지거나 만료된 뒤에는 관찰의 본문과 요청 alias로 그 내용을 다시 꺼낼 수 없어야 한다.
첨부 삭제의 사용자·대화·첨부 잠금과 요청 커밋 순서는 [ADR-20261010 / attachment-deletion-request](ADR-20261010-attachment-deletion-request.md)를 따른다.

### 결정

`media_observation`은 첨부별 불변 revision이다. 새 요청 UUID는 현재 revision에 대한 CAS를 통과하면 같은 모델 결과라도 새 revision을 만든다.
새 MODEL 제출은 현재 USER 정정을 대체할 수 없다. 요청 UUID마다 `media_observation_request`에 최초 수락한 revision과 고정 요청 hash를 함께 적는다.
같은 UUID와 같은 내용은 CAS에 앞서 그 revision을 반환하고, 내용이 다르면 현재 revision만 담은 409를 낸다.
서버 관측 시각은 새 revision의 마이크로초 단위 생성 시각이다. 요청 hash에 넣지 않으며 재시도에서도 최초 값을 유지한다.

관찰 본문 JSON은 [사용자별 암호화](ADR-20261008-data-encryption.md)의 `TextCipher`를 쓴다.
AAD는 `media_observation:<id>:conversation:<conversation_id>:user:<owner_user_id>`다.
행 번호를 얻고 같은 트랜잭션에서 암호문과 key ID를 채운 뒤 alias까지 저장한다.
새 쓰기에서 암호화할 수 없으면 503으로 거절하고 관찰과 alias를 모두 되돌린다.
이미 있는 null-key 본문만 평문 호환으로 읽으며, key ID가 있으면 enabled 값과 관계없이 복호화를 시도한다.
SQL 장애는 요청 실패로 전파한다. 복호화·JSON 검증 실패는 본문 없이 NEEDS_REVIEW/CONTENT_UNAVAILABLE로 반환한다.

원본 SHA-256과 크기는 원본 스트림으로 직접 확인한다. 파일 이름과 호출자가 준 지문은 근거로 쓰지 않는다.
같은 크기로 바꾼 원본도 쓰기와 과거 UUID 재시도는 409로 막고, 목록은 현재 지문과 본문 없는 NEEDS_REVIEW를 낸다.
현재 대화 주인, 업로더와 관찰 주인이 같아야 읽고 쓸 수 있다.
응답 직전 최신 SQL과 시계를 다시 확인하여 삭제·만료와 소유 변경을 차단한다.

관찰은 첨부의 보관 기간을 그대로 따른다. 요청 alias에는 별도 만료 시각을 두지 않는다.
alias의 첨부·관찰 복합 FK는 다른 첨부의 관찰을 연결하지 못하게 하고, 관찰·첨부의 물리 삭제는 alias까지 cascade한다.
첨부와 대화 삭제의 사전 callback에서 삭제 요청과 관찰 삭제를 함께 커밋한다.
파일이나 session 삭제가 실패해도 관찰은 다시 꺼낼 수 없다.
만료와 소유 불일치 정리는 대상 하나마다 사용자 잠금을 잡는다.
대화 주인만 바뀌어 업로더와 다른 비정상 후보는 접근을 막고 정리 실패를 기록하며 다른 후보의 정리는 계속한다.

### 대안 기각

- 프로세스 안의 UUID Map: 재시작하면 요청의 원래 revision을 잃는다.
- 최신 관찰 한 행을 덮어쓰기: 과거 UUID의 최초 응답과 USER 정정을 보존할 수 없다.
- alias tombstone과 별도 보관 기간: 첨부 수명 뒤에도 요청과 관찰의 관계가 남는다.
- 암호화가 꺼지면 평문 저장: 관찰·OCR 본문이 데이터베이스와 백업에 드러난다.
- 완료 결과 캐시를 함께 구현: 분석 조건별 재사용과 alias만 추가하는 경합은 별도 계약과 검증이 필요하다.

### 결과와 범위

저장과 조회는 동기 application 서비스로 제공한다. REST/MCP/UI와 실제 분석 호출은 아직 노출하지 않는다.
완료 결과 캐시, analysis key와 캐시 색인도 만들지 않는다.
목록 조회는 원본을 읽고 hash를 계산하는 비용이 들며, 요청마다 최대 100개 첨부로 제한한다.
revision은 첨부 수명 안에만 남고, 삭제 후 분석 결과를 복구하는 이력을 두지 않는다.
본문·OCR·암호문과 외부 오류 원문은 로그에 남기지 않는다.
