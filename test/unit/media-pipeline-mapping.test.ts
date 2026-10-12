import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  createMediaFixture,
  inspectMediaFixture,
  inspectPreparedMediaBatch,
} from "../support/media-pipeline-fixture.ts";
import {
  inspectSubmittedMediaBatch,
  parseMediaAssetId,
  parsePreparedJpegDataUrl,
  validateMediaMapping,
  type ExpectedMediaMapping,
} from "../support/media-pipeline-mapping.ts";

test("실제 JPEG 8방향과 30장 역순 매핑을 검증하고 잘못된 대응을 거부한다", () => {
  const start = performance.now();
  const dir = mkdtempSync(join(tmpdir(), "media-prepared-"));
  try {
    const fixtures = createMediaFixture(dir, 30);
    const vectors: {
      orientation: number;
      width: number;
      height: number;
      pixels: number[];
    }[] = JSON.parse(
      execFileSync(
        "java",
        [
          "-Djava.awt.headless=true",
          join(import.meta.dirname, "../support/PreparedMediaFixture.java"),
          dir,
        ],
        {
          timeout: 60_000,
          encoding: "utf8",
          maxBuffer: 1024 * 1024,
          stdio: ["ignore", "pipe", "pipe"],
        },
      ),
    );
    assert.equal(vectors.length, 8);
    for (const { orientation, width, height, pixels } of vectors) {
      assert.deepEqual(
        [width, height],
        orientation <= 4 ? [224, 160] : [160, 224],
      );
      assert.equal(pixels.length, 16);
      for (const [bit, rgb] of pixels.entries()) {
        const white = ((0xa500 | orientation) & (1 << (15 - bit))) !== 0;
        assert.ok(
          [(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255].every((channel) =>
            white ? channel >= 192 : channel <= 64,
          ),
        );
      }
    }
    // red에서 확인한 기존 원본 디코더의 한계를 회귀 계약으로 남긴다.
    assert.throws(
      () => inspectMediaFixture(join(dir, "received-25.jpeg"), "JPEG", 6),
      /JDK 실행 실패/,
    );
    const expected: ExpectedMediaMapping[] = fixtures
      .toReversed()
      .map((asset, position) => ({
        assetKey: asset.assetKey,
        assetId: String(1000 + asset.expectedMarker),
        position,
        ordinal: position + 1,
        expectedMarker: asset.expectedMarker,
        sourceSha256: asset.bytesSha256!,
        orientation: asset.orientation!,
      }));
    const listed = expected.toReversed().map((item) => ({
      assetId: item.assetId,
      ordinal: item.ordinal,
      sourceFingerprint: item.sourceSha256,
    }));
    const images = expected.map((item) => ({
      label: `${item.ordinal}번째 사진`,
      url: `data:image/jpeg;base64,${readFileSync(join(dir, `received-${item.ordinal}.jpeg`)).toString("base64")}`,
    }));
    const batchStart = performance.now();
    const received = inspectSubmittedMediaBatch(
      images,
      expected.map((item) => item.orientation),
    );
    const batchMs = performance.now() - batchStart;
    const result = validateMediaMapping(expected, listed, received);
    assert.deepEqual(
      result.rows.map((row) => row.recoveredId),
      Array.from({ length: 30 }, (_, i) => 30 - i),
    );
    assert.equal(result.rows[0].ordinal, 1);
    assert.equal(result.rows[0].expectedMarker, 30);
    assert.equal(
      new Set(result.rows.map((row) => row.preparedSha256)).size,
      30,
    );
    for (const row of result.rows) {
      assert.equal(row.scope, "FAKE_HERMES");
      assert.notEqual(row.sourceSha256, row.preparedSha256);
      const bytes = parsePreparedJpegDataUrl(images[row.position].url);
      assert.equal(row.preparedByteLength, bytes.length);
      assert.equal(
        row.preparedSha256,
        createHash("sha256").update(bytes).digest("hex"),
      );
    }
    assert.equal(result.modelSummary, "MODEL_UNVERIFIED");
    assert.equal(result.realProvider, "UNMEASURED");
    assert.equal(result.semanticAccuracy, "UNMEASURED");
    for (const change of [
      { width: 160 },
      { height: 224 },
      { byteLength: 0 },
      { byteLength: 4.5 },
      { bytesSha256: "invalid" },
    ]) {
      assert.throws(
        () =>
          validateMediaMapping(
            expected,
            listed,
            received.map((item, index) =>
              index === 0 ? { ...item, ...change } : item,
            ),
          ),
        /PREPARED_MEASUREMENT_INVALID/,
      );
    }
    for (const [change, code] of [
      [{ assetKey: expected[1].assetKey }, "DUPLICATE_ASSET_KEY"],
      [{ assetId: expected[1].assetId }, "DUPLICATE_ASSET_ID"],
      [{ sourceSha256: expected[1].sourceSha256 }, "DUPLICATE_SOURCE_SHA"],
      [{ expectedMarker: expected[1].expectedMarker }, "DUPLICATE_MARKER"],
      [{ assetId: "１００１" }, "ASSET_ID_INVALID"],
      [{ sourceSha256: "invalid" }, "EXPECTED_MAPPING_INVALID"],
      [{ ordinal: 0 }, "EXPECTED_MAPPING_INVALID"],
    ] as const) {
      assert.throws(
        () =>
          validateMediaMapping(
            expected.map((item, index) =>
              index === 0 ? { ...item, ...change } : item,
            ),
            listed,
            received,
          ),
        new RegExp(code),
      );
    }
    assert.throws(
      () => validateMediaMapping(expected, listed, received.slice(1)),
      /COUNT_MISMATCH/,
    );
    assert.throws(
      () => validateMediaMapping(expected, listed.slice(1), received),
      /COUNT_MISMATCH/,
    );
    const duplicate = received.map((item, index) =>
      index === 1 ? { ...received[0], label: item.label } : item,
    );
    assert.throws(
      () => validateMediaMapping(expected, listed, duplicate),
      /DUPLICATE_PREPARED/,
    );
    const shuffle = received.map((item, index) =>
      index < 2 ? { ...received[1 - index], label: item.label } : item,
    );
    assert.throws(
      () => validateMediaMapping(expected, listed, shuffle),
      /MARKER_MISMATCH/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed,
          received.map((item, index) =>
            index === 0 ? { ...item, label: "１번째 사진" } : item,
          ),
        ),
      /LABEL_MISMATCH/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed.map((item, index) =>
            index === 0
              ? { ...item, sourceFingerprint: received[0].bytesSha256 }
              : item,
          ),
          received,
        ),
      /SOURCE_SHA_MISMATCH/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed.map((item, index) =>
            index === 0
              ? { ...item, sourceFingerprint: listed[1].sourceFingerprint }
              : item,
          ),
          received,
        ),
      /SOURCE_SHA_MISMATCH/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected.map((item, index) =>
            index === 0 ? { ...item, position: 1 } : item,
          ),
          listed,
          received,
        ),
      /EXPECTED_MAPPING_INVALID/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed.map((item, index) =>
            index === 0 ? { ...item, ordinal: 2 } : item,
          ),
          received,
        ),
      /LISTED_MAPPING_MISMATCH/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed.map((item, index) => (index === 0 ? listed[1] : item)),
          received,
        ),
      /DUPLICATE_LISTED/,
    );
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed,
          received.map((item, index) =>
            index === 0
              ? { ...item, recoveredId: received[1].recoveredId }
              : item,
          ),
        ),
      /DUPLICATE_RECOVERED/,
    );
    const failed = inspectPreparedMediaBatch([
      { file: join(dir, "received-25.jpeg"), orientation: 8 },
      { file: join(dir, "corrupt.jpeg"), orientation: 1 },
      { file: join(dir, "ambiguous.jpeg"), orientation: 1 },
      { file: join(dir, "ambiguous.png"), orientation: 1 },
      { file: join(dir, "received-25.jpeg"), orientation: 1 },
    ]);
    assert.equal(failed[0].recoveredId, null);
    assert.equal(failed[0].errorCode, "MARKER_UNREADABLE");
    assert.equal(failed[1].errorCode, "DECODE_FAILED");
    assert.equal(failed[2].errorCode, "MARKER_UNREADABLE");
    assert.equal(failed[3].errorCode, "NOT_JPEG");
    assert.equal(failed[4].errorCode, "DIMENSION_MISMATCH");
    assert.throws(
      () =>
        validateMediaMapping(
          expected,
          listed,
          received.map((item, index) =>
            index === 0 ? { ...failed[2], label: item.label } : item,
          ),
        ),
      /PREPARED_DECODE_FAILED/,
    );
    assert.throws(() => inspectPreparedMediaBatch([]), /1~30/);
    assert.throws(
      () =>
        inspectPreparedMediaBatch(
          Array(31).fill({ file: "unused", orientation: 1 }),
        ),
      /1~30/,
    );
    assert.throws(
      () => inspectPreparedMediaBatch([{ file: "unused", orientation: 9 }]),
      /방향/,
    );
    console.log(
      `합성 장수=30, prepared 배치=${batchMs.toFixed(0)}ms, 전체 unit=${(performance.now() - start).toFixed(0)}ms`,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("JPEG data URL과 문자열 asset ID의 파싱 경계를 검사한다", () => {
  const valid = "data:image/jpeg;base64,/9gAAA==";
  assert.equal(parsePreparedJpegDataUrl(valid).length, 4);
  for (const value of [
    "data:image/png;base64,/9gAAA==",
    valid + "\n",
    valid.replace("AAA==", "AAB=="),
    "data:image/jpeg;base64,/9gAAA",
    "data:image/jpeg;base64,AAAAAA==",
    "data:image/jpeg;base64,",
    "data:image/jpeg;base64,%%%%",
  ]) {
    assert.throws(() => parsePreparedJpegDataUrl(value), /DATA_URL_INVALID/);
  }
  assert.equal(parseMediaAssetId("9223372036854775807"), "9223372036854775807");
  assert.equal(parseMediaAssetId("9007199254740993"), "9007199254740993");
  for (const value of [
    "１",
    "١",
    "1\n",
    " 1",
    "+1",
    "01",
    "0",
    "1.0",
    "1e2",
    "9223372036854775808",
    1,
    null,
  ]) {
    assert.throws(() => parseMediaAssetId(value), /ASSET_ID_INVALID/);
  }
  assert.throws(() => inspectSubmittedMediaBatch([], []), /BATCH_COUNT/);
  assert.throws(
    () => inspectSubmittedMediaBatch([{ label: "1번째 사진", url: valid }], []),
    /BATCH_COUNT/,
  );
  assert.throws(
    () =>
      inspectSubmittedMediaBatch(
        [{ label: "1번째 사진", url: valid.repeat(350000) }],
        [1],
      ),
    /PAYLOAD_TOO_LARGE/,
  );
  // 최대 예산 근처의 입력도 엄격 파서가 읽는다. JPEG 내용 검사는 Java가 별도로 한다.
  const large = Buffer.alloc(3 * 1024 * 1024);
  large[0] = 0xff;
  large[1] = 0xd8;
  const largeUrl = `data:image/jpeg;base64,${large.toString("base64")}`;
  assert.equal(parsePreparedJpegDataUrl(largeUrl).length, large.length);
  assert.throws(
    () =>
      inspectSubmittedMediaBatch(
        [
          { label: "1번째 사진", url: largeUrl },
          { label: "2번째 사진", url: largeUrl },
        ],
        [1, 1],
      ),
    /PAYLOAD_TOO_LARGE/,
  );
});
