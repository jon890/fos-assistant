## ADR-20261102 / media-observation-request-merge: 관찰 저장 요청은 관찰 줄에 두고 완료 결과 재사용을 없앤다

- **status**: `accepted`
- Date: 2026-11-02

### 맥락

[관찰 저장 ADR](ADR-20261010-media-observation-storage.md)은 요청 UUID 를 `media_observation_request` 테이블의 alias 줄로 두었다.
[완료 결과 재사용 ADR](archive/ADR-20261011-media-observation-cache.md)은 새 UUID 를 이미 있는 관찰에 alias 줄로 이어, 같은 분석 조건의 제출이 revision 을 늘리지 않게 했다.
사용자는 쓰지 않는 테이블을 줄이기로 하고(2026-11-01, 2026-11-02), 사진 관찰 두 테이블을 하나로 합치기로 정했다.
운영의 두 테이블은 0행이다. MCP 기록은 provider 와 model 이 `UNKNOWN` 이라 재사용 대상이 아니고, 재사용을 쓰는 다른 경로는 없다.

### 결정

저장 요청 하나가 관찰 revision 하나를 만든다. 요청 UUID 와 요청 hash 는 그 revision 줄의 `request_id`, `request_hash` 에 둔다.
같은 첨부의 같은 UUID 는 유일 제약 `(attachment_id, request_id)` 가 막는다.
같은 UUID 와 같은 내용의 재시도는 그 UUID 로 만든 revision 을 돌려주고, 내용이 다르면 현재 revision 을 담은 409 다.
새 UUID 는 분석 조건이 최신 완료 revision 과 같아도 새 revision 을 만든다. 완료 결과 재사용과 `analysis_key` 는 없앤다.

테이블은 두 단계로 지운다.
첫 PR 은 칸을 더하고 옛 alias 를 옮긴 뒤 코드가 옛 테이블과 `analysis_key` 를 더는 읽고 쓰지 않게 한다. 직전 이미지가 새 스키마에서도 관찰을 쓸 수 있게 두 칸은 NULL 을 허용한다.
그 배포를 확인한 뒤의 PR 이 빈 칸을 다시 채우고 NOT NULL 로 바꾼 다음 `media_observation_request` 와 `analysis_key` 를 지운다.
`ddl-auto: validate` 라서 같은 PR 에서 지우면 직전 이미지로 되돌릴 때 옛 엔티티가 테이블을 찾지 못해 기동하지 못한다. 0행이어도 같다.

### 대안 기각

- 관찰 줄에 alias 목록 JSON 칸을 두어 재사용을 남기기: 불변 관찰 줄을 재사용 때마다 고쳐야 하고, 요청 UUID 유일을 DB 가 지키지 못한다.
- 재사용 hit 에서 alias 를 남기지 않고 응답만 돌려주기: 그 UUID 를 재시도할 때 최신 revision 이 바뀌었으면 409 가 나서 같은 요청이 다른 결과를 낸다.
- 두 테이블을 그대로 두기: 0행인 테이블과 쓰지 않는 재사용 경로를 계속 유지해야 한다.

### 결과

- 얻는 것: 테이블이 하나 줄고, 재사용의 분석 key 계산과 후보 검사가 없어진다. 관찰 줄을 지우면 요청 기록도 함께 사라져 cascade 가 필요 없다.
- 감당할 것: 같은 분석 조건의 다른 실행 제출도 revision 을 하나씩 늘린다. 옮길 때 같은 관찰을 가리키던 두 번째 이후 alias(재사용 hit)는 옮기지 않아 그 UUID 의 재시도는 새 요청으로 다뤄진다. 운영 0행이라 실제로 잃는 줄은 없다.
