# 사진 처리의 단계별 합성 판정

사진 전달과 내용 판독은 별도 증거로 판정한다.
합성 검사는 자체 manifest와 사건만 사용하며 실제 원고 저장이나 외부 서비스를 호출하지 않는다.

covers: `test/support/media-pipeline-evidence.ts`, `test/unit/media-pipeline-evidence.test.ts`

## 단계별 증거

`PipelineEvidenceV1`의 사건 정의와 입력 검사는 [판정기](../../test/support/media-pipeline-evidence.ts)가 갖는다.
전달된 ID, 실제 픽셀에서 회수한 ID, 원고에 포함한 ID를 따로 집계한다.
중복 사건과 다른 사진의 표식, 허용하지 않은 ID는 거절한다.
일부 사진만 포함한 원고는 포함·빠진 ID를 표시하며 전체 사진을 사용했다고 판정하지 않는다.

합성 픽셀 회수는 한국어 OCR 의미 판독을 증명하지 않는다.
작은 한국어 글씨는 사람이 확인해야 하는 상태로 남긴다.
실제 provider와 브라우저, 비용은 측정하지 않았으면 `UNMEASURED`와 null을 유지한다.
전달 사건이나 저장 사건만으로 내용 판독 성공을 만들지 않는다.
