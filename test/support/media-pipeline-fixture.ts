import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

export type PixelSample = {
  x: number;
  y: number;
  displayX: number;
  displayY: number;
  rgb: number;
  displayRgb: number;
};
export type PixelMeasurement = {
  recoveredId: number | null;
  secondFrameId: number | null;
  frames: number;
  byteLength: number;
  bytesSha256: string;
  alpha: number;
  samples: PixelSample[];
  glyphPixels: number[][];
  errorCode?: string;
};
export type MediaFixture = {
  assetKey: string;
  ordinal: number;
  expectedMarker: number;
  expectedKoreanText: string;
  bytesSha256: string | null;
  byteLength?: number;
  format: "JPEG" | "PNG" | "GIF" | "WEBP" | "UNSUPPORTED";
  expectedCoverage: "ORIGINAL" | "FIRST_FRAME";
  frame?: 0;
  fileName?: string;
  orientation?: number;
  markerRecovered?: boolean;
  measurement?: PixelMeasurement;
  actuallyObserved: "PASS" | "FAIL" | "UNMEASURED";
  observationStatus: "NEEDS_REVIEW" | "FAILED";
  errorCode?: "DECODE_FAILED" | "UNSUPPORTED_FORMAT" | "PAYLOAD_TOO_LARGE";
};

const GENERATOR = join(import.meta.dirname, "MediaFixtureGenerator.java");

/** 실제 바이트에서 얻은 결과만 반환하며 Java 오류 원문과 임시 경로를 로그로 내지 않는다. */
function runGenerator(args: string[]): string {
  try {
    return execFileSync(
      "java",
      ["-Djava.awt.headless=true", GENERATOR, ...args],
      {
        encoding: "utf8",
        timeout: 60_000,
        maxBuffer: 2 * 1024 * 1024,
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
  } catch {
    throw new Error("합성 이미지 JDK 실행 실패");
  }
}

export function inspectMediaFixture(
  file: string,
  format: string,
  orientation = 1,
): PixelMeasurement {
  return JSON.parse(
    runGenerator(["inspect", file, format, String(orientation)]),
  );
}

export function createMediaFixture(
  tempDir: string,
  count: number,
): MediaFixture[] {
  if (![1, 10, 11, 25, 30].includes(count))
    throw new Error("지원하지 않는 합성 장수");
  const generated: {
    ordinal: number;
    format: "JPEG" | "PNG" | "GIF";
    orientation: number;
    measurement: PixelMeasurement;
  }[] = JSON.parse(runGenerator([tempDir, String(count)]));
  return generated.map(({ ordinal, format, orientation, measurement }) => {
    const fileName = `asset-${ordinal}.${format.toLowerCase()}`;
    const bytes = readFileSync(join(tempDir, fileName));
    const bytesSha256 = createHash("sha256").update(bytes).digest("hex");
    const signature =
      format === "JPEG"
        ? "ffd8"
        : format === "PNG"
          ? "89504e470d0a1a0a"
          : "47494638";
    if (
      !bytes.toString("hex").startsWith(signature) ||
      measurement.byteLength !== bytes.length ||
      measurement.bytesSha256 !== bytesSha256
    )
      throw new Error("합성 바이트 검증 실패");
    const markerRecovered = measurement.recoveredId === ordinal;
    return {
      assetKey: `synthetic-${ordinal}`,
      ordinal,
      expectedMarker: ordinal,
      expectedKoreanText: "사진",
      bytesSha256,
      byteLength: bytes.length,
      format,
      orientation,
      fileName,
      measurement,
      markerRecovered,
      expectedCoverage: format === "GIF" ? "FIRST_FRAME" : "ORIGINAL",
      ...(format === "GIF" ? { frame: 0 as const } : {}),
      actuallyObserved: markerRecovered ? "PASS" : "FAIL",
      // 작은 글씨의 의미를 OCR로 판독하지 않았으므로 검토 상태를 유지한다.
      observationStatus: "NEEDS_REVIEW",
    };
  });
}

/** 바이트를 만들지 않는 사례는 양성 manifest와 분리한다. */
export function mediaMetadataCases(): MediaFixture[] {
  const base = {
    expectedKoreanText: "사진",
    bytesSha256: null,
    observationStatus: "FAILED" as const,
  };
  return [
    {
      ...base,
      assetKey: "metadata-webp",
      ordinal: 31,
      expectedMarker: 31,
      format: "WEBP",
      expectedCoverage: "FIRST_FRAME",
      frame: 0,
      actuallyObserved: "UNMEASURED",
      observationStatus: "NEEDS_REVIEW",
    },
    {
      ...base,
      assetKey: "failure-corrupt",
      ordinal: 32,
      expectedMarker: 32,
      format: "JPEG",
      expectedCoverage: "ORIGINAL",
      actuallyObserved: "FAIL",
      errorCode: "DECODE_FAILED",
    },
    {
      ...base,
      assetKey: "failure-unsupported",
      ordinal: 33,
      expectedMarker: 33,
      format: "UNSUPPORTED",
      expectedCoverage: "ORIGINAL",
      actuallyObserved: "FAIL",
      errorCode: "UNSUPPORTED_FORMAT",
    },
    {
      ...base,
      assetKey: "failure-size",
      ordinal: 34,
      expectedMarker: 34,
      format: "JPEG",
      expectedCoverage: "ORIGINAL",
      actuallyObserved: "FAIL",
      errorCode: "PAYLOAD_TOO_LARGE",
    },
  ];
}
