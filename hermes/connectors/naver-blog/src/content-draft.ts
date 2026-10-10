import { z } from "zod";

export const CONTENT_COMPILER_VERSION = "content-compiler-v1" as const;
const codePoints = (value: string) => [...value].length;
const text = (min: number, max: number) => z.string().refine(
  (value) => codePoints(value) >= min && codePoints(value) <= max,
  { message: `문자 수는 ${min}자에서 ${max}자까지입니다.` },
);
const singleLine = z.string().refine((value) => !/[\r\n]/.test(value));
const decimalId = singleLine.regex(/^[0-9]+$/);
const revision = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER);
const fingerprint = singleLine.regex(/^[0-9a-f]{64}$/);

// 기존 DSL과 정확히 같은 줄만 예약한다. 기존 모듈의 파일·날짜·난수 의존성은 가져오지 않는다.
const reservedLines = [
  /^\[사진 ([1-9][0-9]{0,2}): ([^/\]]+)\]$/,
  /^\[스티커: ([A-Za-z0-9_-]{1,64})\]$/,
  /^\[지도: ([^|\]]+?) \| ([^\]]+)\]$/,
  /^\[기존 (사진|스티커|지도|구성요소) ([1-9][0-9]*)(?:: [A-Za-z0-9_-]{1,40})?\]$/,
];
const line = (max: number) => text(0, max)
  .refine((value) => !/[\r\n]/.test(value), { message: "문단 안에는 줄바꿈을 넣지 않습니다." })
  .refine((value) => !reservedLines.some((pattern) => pattern.test(value)), {
    message: "CONTENT_DRAFT_RESERVED_LINE",
  });
const paragraphText = line(20_000);
const caption = line(300);
const observationRef = z.strictObject({ id: decimalId, revision });
const stickerCode = singleLine.regex(/^[A-Za-z0-9_-]{1,64}$/);
const mapName = text(1, 200).refine((value) => !/[\r\n\]|]/.test(value));
const mapAddress = text(1, 200).refine((value) => !/[\r\n\]]/.test(value));
const tags = z.array(text(1, 30).refine((value) => !/[#,]/.test(value))).max(30)
  .refine((values) => new Set(values.map((value) => value.trim().toLowerCase())).size === values.length, {
    message: "같은 태그는 한 번만 넣습니다.",
  });
const photoDir = z.string().refine((value) => value.startsWith("/") && !value.includes("\0")
  && !value.split("/").includes(".."));
const fileName = singleLine.regex(/^[A-Za-z0-9._-]+\.(jpg|jpeg|png|webp|gif|heic)$/i)
  .refine((value) => !value.includes("..") && !/\.small\.jpg$/i.test(value));

export const canonicalBlockSchema = z.discriminatedUnion("type", [
  z.strictObject({ type: z.literal("paragraph"), text: paragraphText }),
  z.strictObject({ type: z.literal("image"), assetId: decimalId, caption: caption.optional(),
    observationRef: observationRef.optional() }),
  z.strictObject({ type: z.literal("sticker"), code: stickerCode }),
  z.strictObject({ type: z.literal("map"), name: mapName, address: mapAddress }),
]);

export const canonicalDraftV1Schema = z.strictObject({
  schemaVersion: z.literal(1), draftId: z.uuid(), revision, conversationId: z.uuid(),
  title: text(1, 100), category: text(1, 50), tags,
  blocks: z.array(canonicalBlockSchema).max(500),
}).superRefine((draft, ctx) => {
  for (const [type, max] of [["image", 50], ["sticker", 30], ["map", 30]] as const) {
    if (draft.blocks.filter((block) => block.type === type).length > max)
      ctx.addIssue({ code: "custom", path: ["blocks"], message: `${type}은 ${max}개까지입니다.` });
  }
});

/** 권한·원본 지문·관찰과 asset의 대응은 호출자가 검증한 뒤 넘긴다. */
export const resolvedAssetSchema = z.strictObject({
  assetId: decimalId, sourceFingerprint: fingerprint, fileName, photoDir,
  ordinal: revision, observationNote: text(0, 300).optional(),
});

export const canonicalBlockTupleSchema = z.union([
  z.tuple([z.literal("paragraph"), paragraphText]),
  z.tuple([z.literal("image"), decimalId, caption.nullable(),
    z.tuple([decimalId, revision]).nullable()]),
  z.tuple([z.literal("sticker"), stickerCode]),
  z.tuple([z.literal("map"), mapName, mapAddress]),
]);

export const compiledSnapshotV1Schema = z.strictObject({
  schemaVersion: z.literal(1), compilerVersion: z.literal(CONTENT_COMPILER_VERSION),
  draftId: z.uuid(), revision,
  canonicalBlocks: z.array(canonicalBlockTupleSchema).max(500),
  payload: z.strictObject({ title: text(1, 100), category: text(1, 50), tags,
    body: text(0, 20_000), photo_dir: photoDir.optional() }),
  photoNotes: z.record(singleLine.regex(/^(?:[1-9]|[1-4][0-9]|50)$/), text(0, 300)),
  assetManifest: z.array(z.strictObject({ position: z.number().int().min(0).max(499),
    assetId: decimalId, sourceFingerprint: fingerprint, fileName })).max(50),
  payloadFingerprint: fingerprint, snapshotHash: fingerprint,
});

export type CanonicalDraftV1 = z.infer<typeof canonicalDraftV1Schema>;
export type CanonicalBlock = z.infer<typeof canonicalBlockSchema>;
export type CanonicalBlockTuple = z.infer<typeof canonicalBlockTupleSchema>;
export type ResolvedAsset = z.infer<typeof resolvedAssetSchema>;
export type CompiledSnapshotV1 = z.infer<typeof compiledSnapshotV1Schema>;
