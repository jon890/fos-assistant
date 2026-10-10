import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  createMediaFixture,
  inspectMediaFixture,
  mediaMetadataCases,
} from "../support/media-pipeline-fixture.ts";

const coordinates = [
  [28, 28],
  [195, 28],
  [195, 131],
  [28, 131],
  [28, 28],
  [131, 28],
  [131, 195],
  [28, 195],
];

for (const count of [1, 10, 11, 25, 30]) {
  test(`${count}장 실제 바이트와 ID가 1:1로 대응한다`, () => {
    const dir = mkdtempSync(join(tmpdir(), "media-fixture-"));
    try {
      const manifest = createMediaFixture(dir, count);
      assert.equal(manifest.length, count);
      assert.equal(
        new Set(manifest.map((asset) => asset.assetKey)).size,
        count,
      );
      assert.equal(
        new Set(manifest.map((asset) => asset.bytesSha256)).size,
        count,
      );
      for (const asset of manifest) {
        const measured = asset.measurement!;
        assert.equal(asset.ordinal, asset.expectedMarker);
        assert.equal(measured.recoveredId, asset.ordinal);
        assert.equal(asset.markerRecovered, true);
        assert.equal(asset.actuallyObserved, "PASS");
        assert.equal(asset.observationStatus, "NEEDS_REVIEW");
        assert.equal(asset.expectedKoreanText, "사진");
        const bytes = readFileSync(join(dir, asset.fileName!));
        assert.equal(bytes.length, measured.byteLength);
        assert.equal(
          createHash("sha256").update(bytes).digest("hex"),
          asset.bytesSha256,
        );
        for (const [bit, sample] of measured.samples.entries()) {
          const expectedWhite =
            ((0xa500 | asset.ordinal) & (1 << (15 - bit))) !== 0;
          const channels = [
            (sample.rgb >> 16) & 255,
            (sample.rgb >> 8) & 255,
            sample.rgb & 255,
          ];
          if (asset.format === "JPEG") {
            assert.ok(
              channels.every((channel) =>
                expectedWhite ? channel >= 192 : channel <= 64,
              ),
            );
          } else
            assert.deepEqual(
              channels,
              expectedWhite ? [255, 255, 255] : [0, 0, 0],
            );
          assert.equal(sample.rgb, sample.displayRgb);
        }
        if (asset.ordinal <= 8) {
          assert.equal(bytes.subarray(6, 12).toString("ascii"), "Exif\0\0");
          assert.equal(bytes.readUInt16LE(30), asset.ordinal);
          assert.deepEqual(
            [measured.samples[0].displayX, measured.samples[0].displayY],
            coordinates[asset.ordinal - 1],
          );
          assert.deepEqual(
            [measured.samples[0].x, measured.samples[0].y],
            [28, 28],
          );
        }
        if (asset.format === "PNG") assert.equal(measured.alpha, 0);
        if (asset.format !== "JPEG") {
          const glyph = [
            "00001000",
            "01001000",
            "01001100",
            "10101000",
            "10101000",
            "00001000",
            "00001000",
            "00001000",
            "11101000",
            "01001000",
            "10101000",
            "00001000",
            "00000000",
            "10000000",
            "10000000",
            "11111110",
          ];
          assert.deepEqual(
            measured.glyphPixels,
            glyph.map((row) =>
              [...row].map((pixel) => (pixel === "1" ? 0 : 0xffffff)),
            ),
          );
        }
        if (asset.format === "GIF") {
          assert.equal(measured.frames, 2);
          assert.equal(measured.secondFrameId, asset.ordinal + 64);
          assert.notEqual(measured.secondFrameId, measured.recoveredId);
          assert.equal(asset.expectedCoverage, "FIRST_FRAME");
          assert.equal(asset.frame, 0);
        }
      }
      assert.equal(
        inspectMediaFixture(join(dir, "ambiguous.png"), "PNG").recoveredId,
        null,
      );
      // JPEG의 중간 RGB도 PASS가 되지 않는지 손실 없는 중간색 입력으로 임계 분기를 검사한다.
      assert.equal(
        inspectMediaFixture(join(dir, "ambiguous.png"), "JPEG").recoveredId,
        null,
      );
      assert.equal(
        inspectMediaFixture(join(dir, "corrupt.jpeg"), "JPEG").errorCode,
        "DECODE_FAILED",
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
}

test("metadata 실패와 WebP 미측정을 실제 이미지에서 분리한다", () => {
  const [webp, corrupt, unsupported, oversized] = mediaMetadataCases();
  assert.equal(webp.bytesSha256, null);
  assert.equal(webp.markerRecovered, undefined);
  assert.equal(webp.actuallyObserved, "UNMEASURED");
  assert.equal(webp.expectedCoverage, "FIRST_FRAME");
  assert.equal(webp.frame, 0);
  assert.equal(corrupt.errorCode, "DECODE_FAILED");
  assert.equal(unsupported.errorCode, "UNSUPPORTED_FORMAT");
  assert.equal(oversized.errorCode, "PAYLOAD_TOO_LARGE");
  assert.throws(() => createMediaFixture("unused", 0), /장수/);
  assert.throws(() => createMediaFixture("unused", 31), /장수/);
});
