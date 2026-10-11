import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  inspectPreparedMediaBatch,
  type PreparedPixelMeasurement,
} from "./media-pipeline-fixture.ts";

const DATA_URL_PREFIX = "data:image/jpeg;base64,";
const MAX_DATA_URL_BYTES = 7 * 1024 * 1024;
const SHA = /^[0-9a-f]{64}$/;

export type ExpectedMediaMapping = {
  assetKey: string;
  assetId: string;
  position: number;
  ordinal: number;
  expectedMarker: number;
  sourceSha256: string;
  orientation: number;
};
export type ListedMediaMapping = {
  assetId: string;
  ordinal: number;
  sourceFingerprint: string;
};
export type ReceivedMediaPixels = PreparedPixelMeasurement & { label: string };
export type MediaMappingRow = {
  assetKey: string;
  assetId: string;
  position: number;
  ordinal: number;
  expectedMarker: number;
  recoveredId: number;
  sourceSha256: string;
  preparedSha256: string;
  preparedByteLength: number;
  width: number;
  height: number;
  scope: "FAKE_HERMES";
};

/** JSON의 문자열 ID를 반올림 없이 비교한다. Unicode 숫자와 공백은 허용하지 않는다. */
export function parseMediaAssetId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error("ASSET_ID_INVALID");
  return value;
}

export function parsePreparedJpegDataUrl(url: string): Buffer {
  if (
    typeof url !== "string" ||
    url.length > MAX_DATA_URL_BYTES ||
    !url.startsWith(DATA_URL_PREFIX)
  )
    throw new Error("DATA_URL_INVALID");
  const encoded = url.slice(DATA_URL_PREFIX.length);
  if (
    !encoded ||
    encoded.length % 4 !== 0 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)
  )
    throw new Error("DATA_URL_INVALID");
  const bytes = Buffer.from(encoded, "base64");
  if (
    bytes.toString("base64") !== encoded ||
    bytes.length < 4 ||
    bytes[0] !== 0xff ||
    bytes[1] !== 0xd8
  )
    throw new Error("DATA_URL_INVALID");
  return bytes;
}

/** 대역의 수신 목록을 한 번에 읽는다. 파일 이름과 정답 ID는 디코더 입력에 없다. */
export function inspectSubmittedMediaBatch(
  images: readonly { label: string; url: string }[],
  orientations: readonly number[],
): ReceivedMediaPixels[] {
  if (
    images.length < 1 ||
    images.length > 30 ||
    images.length !== orientations.length
  )
    throw new Error("BATCH_COUNT_INVALID");
  if (
    images.reduce((sum, image) => sum + image.url.length, 0) >
    MAX_DATA_URL_BYTES
  )
    throw new Error("PAYLOAD_TOO_LARGE");
  const bytes = images.map((image) => parsePreparedJpegDataUrl(image.url));
  const dir = mkdtempSync(join(tmpdir(), "media-received-"));
  try {
    const measured = inspectPreparedMediaBatch(
      bytes.map((image, position) => {
        const file = join(dir, `received-${position}.jpeg`);
        writeFileSync(file, image);
        return { file, orientation: orientations[position] };
      }),
    );
    if (measured.length !== images.length)
      throw new Error("DECODE_COUNT_MISMATCH");
    return measured.map((item, position) => {
      if (
        item.byteLength !== bytes[position].length ||
        item.bytesSha256 !==
          createHash("sha256").update(bytes[position]).digest("hex")
      )
        throw new Error("PREPARED_BYTES_MISMATCH");
      return { ...item, label: images[position].label };
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** CP 목록과 수신 픽셀을 독립적으로 대조하는 테스트 계약이다. 모델의 의미 판독은 검증하지 않는다. */
export function validateMediaMapping(
  expected: readonly ExpectedMediaMapping[],
  listed: readonly ListedMediaMapping[],
  received: readonly ReceivedMediaPixels[],
): {
  rows: MediaMappingRow[];
  modelSummary: "MODEL_UNVERIFIED";
  realProvider: "UNMEASURED";
  semanticAccuracy: "UNMEASURED";
} {
  if (
    expected.length < 1 ||
    expected.length > 30 ||
    listed.length !== expected.length ||
    received.length !== expected.length
  )
    throw new Error("MAPPING_COUNT_MISMATCH");
  const unique = (values: readonly unknown[], code: string) => {
    if (new Set(values).size !== values.length) throw new Error(code);
  };
  unique(
    expected.map((item) => item.assetKey),
    "DUPLICATE_ASSET_KEY",
  );
  unique(
    expected.map((item) => parseMediaAssetId(item.assetId)),
    "DUPLICATE_ASSET_ID",
  );
  unique(
    expected.map((item) => item.expectedMarker),
    "DUPLICATE_MARKER",
  );
  unique(
    expected.map((item) => item.sourceSha256),
    "DUPLICATE_SOURCE_SHA",
  );
  unique(
    listed.map((item) => parseMediaAssetId(item.assetId)),
    "DUPLICATE_LISTED_ASSET",
  );
  unique(
    received.map((item) => item.bytesSha256),
    "DUPLICATE_PREPARED_SHA",
  );
  unique(
    received.map((item) => item.recoveredId),
    "DUPLICATE_RECOVERED_ID",
  );
  const byAsset = new Map(listed.map((item) => [item.assetId, item]));
  const rows = expected.map((item, position): MediaMappingRow => {
    if (
      !item.assetKey.trim() ||
      item.position !== position ||
      item.ordinal !== position + 1 ||
      !Number.isInteger(item.expectedMarker) ||
      item.expectedMarker < 1 ||
      item.expectedMarker > 30 ||
      !Number.isInteger(item.orientation) ||
      item.orientation < 1 ||
      item.orientation > 8 ||
      !SHA.test(item.sourceSha256)
    )
      throw new Error("EXPECTED_MAPPING_INVALID");
    const stored = byAsset.get(item.assetId);
    if (!stored || stored.ordinal !== item.ordinal)
      throw new Error("LISTED_MAPPING_MISMATCH");
    if (stored.sourceFingerprint !== item.sourceSha256)
      throw new Error("SOURCE_SHA_MISMATCH");
    const pixel = received[position];
    if (pixel.label !== `${item.ordinal}번째 사진`)
      throw new Error("LABEL_MISMATCH");
    if (pixel.errorCode || pixel.recoveredId === null)
      throw new Error("PREPARED_DECODE_FAILED");
    if (pixel.recoveredId !== item.expectedMarker)
      throw new Error("MARKER_MISMATCH");
    if (
      pixel.width !== (item.orientation >= 5 ? 160 : 224) ||
      pixel.height !== (item.orientation >= 5 ? 224 : 160) ||
      !Number.isSafeInteger(pixel.byteLength) ||
      pixel.byteLength < 4 ||
      !SHA.test(pixel.bytesSha256)
    )
      throw new Error("PREPARED_MEASUREMENT_INVALID");
    return {
      assetKey: item.assetKey,
      assetId: item.assetId,
      position,
      ordinal: item.ordinal,
      expectedMarker: item.expectedMarker,
      recoveredId: pixel.recoveredId,
      sourceSha256: item.sourceSha256,
      preparedSha256: pixel.bytesSha256,
      preparedByteLength: pixel.byteLength,
      width: pixel.width,
      height: pixel.height,
      scope: "FAKE_HERMES",
    };
  });
  return {
    rows,
    modelSummary: "MODEL_UNVERIFIED",
    realProvider: "UNMEASURED",
    semanticAccuracy: "UNMEASURED",
  };
}
