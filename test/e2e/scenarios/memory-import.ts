/**
 * 검토한 묶음을 미리보고, 들이고, 다시 올려 중복으로 답받는 흐름을 한 번 왕복한다(ADR-058).
 *
 * <p>운영과 같은 조립(보안 설정, 암호화 설정, Flyway 로 만든 스키마)에서 본다. 경우마다의 판정은 backend 테스트가 이미
 * 봤다. 출처가 같은 줄을 막는 유일 제약 `uk_memory_user_source` 는 Flyway 를 지나는 이 검사에서만 실제로 붙는다.
 */
import { randomUUID } from "node:crypto";
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type ImportResponse = {
  newCount: number;
  duplicateCount: number;
  conflictCount: number;
  rejectedCount: number;
  items: { index: number; status: string; reason: string | null; memoryId: number | null }[];
};

type DocumentView = { content: string; sensitive: boolean; revision: number };

type DocumentSummary = { documentKey: string };

type MemoryView = { title: string };

type IssuedToken = { token: string; info: { id: number } };

const MARK = "평문-표식-7391";

export const memoryImportScenario: Scenario = {
  name: "기존 지식 가져오기",

  async run(context) {
    const suffix = randomUUID().slice(0, 8);
    const key = `e2e-imported-${suffix}`;
    const dad = context.tokens.dad;
    const bundle = (items: unknown[]) => ({ schemaVersion: 1, items });
    const memoryItem = {
      sourceRef: `private/wiki/sample/e2e-${suffix}-memory.md`,
      sourceDate: "2026-01-02",
      collection: "core",
      entryType: "MEMORY",
      documentKey: null,
      title: `가져온 기억 ${suffix}`,
      content: "일하는 방식 본문",
      sensitive: false,
      retrieval: "SEARCH",
    };
    const documentItem = {
      sourceRef: `private/wiki/sample/e2e-${suffix}-doc.md`,
      sourceDate: "2026-01-03",
      collection: "career",
      entryType: "DOCUMENT",
      documentKey: key,
      title: `가져온 문서 ${suffix}`,
      content: MARK,
      sensitive: true,
      retrieval: "SEARCH",
    };
    const pair = bundle([memoryItem, documentItem]);
    const post = (path: string, token: string | undefined, body: unknown) =>
      call(context, path, { method: "POST", token, body });

    step("미리보기는 저장하지 않는다");
    const previewed = expectStatus(await post("/memory-imports/preview", dad, pair), 200, "미리보기").json<ImportResponse>();
    expect(previewed.newCount === 2, `미리보기의 새 항목 수가 다르다: ${JSON.stringify(previewed)}`);
    const before = expectStatus(await call(context, "/memory-documents", { token: dad }), 200, "문서 목록").json<
      DocumentSummary[]
    >();
    expect(before.every((document) => document.documentKey !== key), "미리보기가 문서를 저장했다");

    step("들이면 생긴다");
    const imported = expectStatus(await post("/memory-imports", dad, pair), 200, "들이기").json<ImportResponse>();
    expect(
      imported.newCount === 2 && imported.items.every((item) => item.memoryId !== null),
      `들인 결과가 다르다: ${JSON.stringify(imported)}`,
    );
    const ids = imported.items.map((item) => item.memoryId as number);
    const document = expectStatus(await call(context, `/memory-documents/${ids[1]}`, { token: dad }), 200, "들인 문서").json<
      DocumentView
    >();
    expect(
      document.content === MARK && document.sensitive && document.revision === 1,
      `들인 문서의 본문이나 판이 다르다: ${JSON.stringify(document)}`,
    );

    step("다시 올리면 중복이다");
    const again = expectStatus(await post("/memory-imports", dad, pair), 200, "다시 들이기").json<ImportResponse>();
    expect(again.duplicateCount === 2 && again.newCount === 0, `다시 올린 결과가 다르다: ${JSON.stringify(again)}`);

    step("다른 사용자는 따로 들인다");
    const other = expectStatus(
      await post("/memory-imports/preview", context.tokens.kid, pair),
      200,
      "다른 사용자의 미리보기",
    ).json<ImportResponse>();
    expect(other.newCount === 2, `다른 사용자의 새 항목 수가 다르다: ${JSON.stringify(other)}`);

    step("신원 항목은 거절한다");
    const held = expectStatus(
      await post(
        "/memory-imports/preview",
        dad,
        bundle([{ ...documentItem, collection: "identity", sourceRef: `private/wiki/sample/e2e-${suffix}-id.md`, documentKey: `id-${suffix}` }]),
      ),
      200,
      "신원 항목",
    ).json<ImportResponse>();
    expect(
      held.items[0]?.status === "REJECTED" && held.items[0]?.reason === "IDENTITY_HELD",
      `신원 항목의 결과가 다르다: ${JSON.stringify(held)}`,
    );

    step("서비스 토큰과 토큰 없는 요청은 들이지 못한다");
    const denied = bundle([{ ...memoryItem, sourceRef: `private/wiki/sample/e2e-${suffix}-denied.md`, title: `거절 확인 ${suffix}` }]);
    expectStatus(await post("/memory-imports", undefined, denied), 403, "토큰 없는 들이기");
    const issued = expectStatus(
      await post("/service-tokens", context.tokens.aunt, {
        label: "e2e",
        expiresInDays: 30,
        collections: [{ collection: "core", allowSensitive: false }],
      }),
      200,
      "서비스 토큰 발급",
    ).json<IssuedToken>();
    expectStatus(await post("/memory-imports", issued.token, denied), 403, "서비스 토큰으로 들이기");
    expectStatus(
      await call(context, `/service-tokens/${issued.info.id}`, { method: "DELETE", token: context.tokens.aunt }),
      200,
      "토큰 폐기",
    );
    for (const token of [dad, context.tokens.aunt]) {
      const memories = expectStatus(await call(context, "/memories", { token }), 200, "Memory 목록").json<MemoryView[]>();
      expect(memories.every((memory) => memory.title !== `거절 확인 ${suffix}`), "거절한 요청이 저장됐다");
    }

    step("뒤 시나리오에 남기지 않는다");
    for (const id of ids) {
      expectStatus(await call(context, `/memories/${id}`, { method: "DELETE", token: dad }), 200, "들인 항목 지우기");
    }
  },
};
