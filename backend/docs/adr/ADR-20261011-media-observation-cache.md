## ADR-20261011 / media-observation-cache: 현재 최신 MODEL 완료 결과를 분석 조건으로 재사용하고 새 요청 alias만 저장한다

- **status**: `accepted`
- Date: 2026-10-11
- **적용 상태**: 구현 전 확정 설계다. 현재 서비스는 PR411의 저장 계약으로 동작한다.

### 맥락

[관찰 저장 ADR](ADR-20261010-media-observation-storage.md)은 불변 revision과 영속 UUID alias를 제공한다.
같은 조건의 완료 제출도 새 revision을 만드는 현재 동작을, 최신 완료 결과 재사용으로 확장한다.
분석 실행을 생략하는 provider cache API는 이번 결정의 대상이 아니다.

### 결정

현재 첨부의 가장 큰 revision 하나만 재사용 후보로 본다.
새 제출과 후보 모두 `MODEL_RESULT`이며 상태가 `SUCCEEDED`, `PARTIAL`, `NEEDS_REVIEW` 중 하나여야 한다.
실제 원본 SHA와 소유·삭제·만료 검사, 기존 UUID 재시도, 현재 revision CAS와 USER 보호를 차례로 통과한 뒤 후보를 비교한다.
`PROCESSING`, `FAILED`, USER 정정과 과거 revision은 후보가 아니다.
표시용 NEEDS_REVIEW 변환이나 `CONTENT_UNAVAILABLE`를 완료 성공으로 해석하지 않는다.

서버가 계산한 `analysis_key`가 같고 `body_key_id`가 null이 아니며 후보 본문을 복호화·파싱·검증할 수 있을 때만 재사용한다.
평문 호환 행은 cache 후보에서 제외한다. 목록과 기존 UUID 재시도는 기존 평문 호환 읽기를 유지한다.
본문은 현재 크기 제한, 저장 출처에 대한 `MediaObservationInput.validate`, 행과 본문의 상태 일치를 모두 통과해야 한다.
후보의 저장 출처와 복호화한 coverage로 계산한 key도 저장 key와 같아야 한다.
본문을 열 수 없거나 key가 불일치하면 miss이며, 같은 CAS에서 제출한 새 암호화 revision을 만든다.
SQL 장애는 miss로 바꾸지 않고 요청 실패로 전파한다.

hit에서는 관찰 ID, revision, 암호문, 본문, 출처와 서버 관측 시각을 보존한다.
새 UUID의 기존 방식 `requestHash`와 후보 관찰 ID를 같은 사용자·대화·첨부 잠금 트랜잭션에서 alias로 저장한다.
같은 분석 조건의 다른 summary, claim, uncertainty나 다른 완료 상태를 제출해도 먼저 저장된 후보 결과가 응답이다.
새 제출의 executionId를 후보 출처에 덧붙이지 않는다.
동일 UUID 재시도는 그 UUID로 처음 제출한 요청 내용의 hash를 요구하며 처음 연결된 결과를 반환한다.

### 분석 조건의 직렬화

UTF-8의 공백 없는 JSON 배열을 SHA-256으로 계산하고 소문자 hex 64자로 저장한다.
배열 순서는 `[sourceFingerprint,schemaVersion,promptVersion,provider,providerVersion,model,modelVersion,coverage]`다.
객체나 map의 필드 순서와 호출자 hash에 의존하지 않는다.
`executionId`, `observedAt`, 완료 상태와 summary 등 결과 본문은 이 조건에서 제외한다.

| 값 | 정규화 |
| --- | --- |
| sourceFingerprint | 실제 원본 스트림으로 계산한 소문자 SHA-256 64자다 |
| schemaVersion | JSON 정수다. 서비스는 현재 지원 버전만 수락한다 |
| promptVersion, provider, model | 입력의 정확한 문자열이다. trim, 대소문자 변환과 Unicode 정규화를 하지 않는다 |
| providerVersion, modelVersion | null은 JSON null이다. 빈 문자열과 null을 구분하고 문자열의 공백도 보존한다 |
| coverage | `[mode,region,frame]` 순서의 배열이다. mode는 현재 허용 문자열을 그대로 쓴다 |
| region | CROP만 `[x,y,width,height]`를 갖는다. 나머지는 null이다 |
| 좌표 | 유한 double을 `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`으로 만든 JSON 문자열이다. 양·음의 0은 모두 `"0"`이다 |
| frame | FIRST_FRAME은 JSON 정수 0이다. 나머지는 null이다 |

coverage의 허용 mode, 범위와 필수 여부는 기존 input 검증을 따른다.
좌표를 반올림하거나 오차 범위로 합치지 않는다.
아주 작은 다른 좌표도 다른 분석 조건이다.
Jackson 3으로 배열과 문자열만 직렬화하며 숫자는 schemaVersion과 frame의 정수뿐이다.

기존 UUID `requestHash` 배열과 input 객체 직렬화는 그대로 둔다.
분석 key 정규화 때문에 과거 alias 재시도의 동일성 의미를 바꾸지 않는다.

### 저장과 수명

`media_observation.analysis_key`는 nullable `CHAR(64)`이며 신규 MODEL 완료 행에만 채운다.
기존 행의 null은 재사용 불가다. 자동 backfill, lazy backfill과 재분석은 하지 않는다.
기존 최신 revision 조회와 key 비교를 사용하며 새 색인, 유일 제약과 별도 cache 저장소는 만들지 않는다.
현재 UNIQUE, 복합 FK, 삭제 cascade와 첨부 수명을 그대로 유지한다.

응답 검사는 호출자 트랜잭션의 스냅샷 밖에서 접근 확인, 새 원본 스트림 읽기와 닫기, 최신 SQL 접근 재확인, 현재 시계와 관찰 만료 확인 순서로 수행한다.
원본을 읽는 동안 커밋한 삭제 요청이나 소유 변경도 마지막 SQL 검사로 차단한다.
목록과 쓰기, cache hit와 기존 UUID 재시도, 새 revision에 같은 순서를 적용한다.
원본이 바뀌면 쓰기 응답은 409이며, 원본이 없거나 만료되면 410이다.
응답 검사가 실패한 경우 이미 커밋한 alias는 남고 이후 UUID 재시도에서도 현재 접근 상태를 먼저 검사한다.
검사를 마친 뒤 발생하는 외부 파일 변경까지 원자적으로 막는 계약은 아니다.

### 대안 기각

- 과거 완료 revision 검색: 최신 상태와 USER 정정을 우회할 수 있다.
- analysis key 유일 색인: 같은 조건의 과거 revision을 허용하는 불변 이력과 충돌한다.
- 프로세스 Map 또는 평문 cache: 재시작 alias와 사용자별 암호화 계약을 잃는다.
- 본문과 executionId까지 분석 조건에 포함: 같은 조건에서 얻은 기존 결과를 재사용할 수 없다.
- 구행 자동 key 보충: 아직 검증하지 않은 과거 결과를 자동으로 후보로 만드는 데이터 변경이다.

### 결과와 적용 범위

현재 revision을 지정한 cache alias 두 요청은 revision 증가 없이 두 alias를 추가하고 모두 성공한다.
실제 새 결과 두 요청이 같은 expectedRevision으로 경쟁하면 한 요청만 새 revision을 만들고 다른 요청은 409다.
추가 원본 재검사는 응답마다 파일 I/O를 늘리지만 권한과 원본 검사를 생략하지 않는다.
실제 provider 성공, 분석 비용 절감, API/MCP/UI 완성과 운영 검증은 이 결정으로 주장하지 않는다.
저장 ADR의 같은 MODEL 완료 제출도 항상 새 revision이라는 부분만 구현 시 대체하며 나머지는 보존한다.
