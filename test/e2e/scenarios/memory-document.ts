/**
 * 사용자가 민감 문서를 만들고 서비스 토큰으로 본문과 판 번호를 읽는 흐름을 한 번 왕복한다.
 *
 * <p>운영과 같은 조립(보안 설정, 인터셉터, 암호화 설정, Flyway 로 만든 스키마)에서 본다. 경우마다의 세부는 backend 테스트가
 * 이미 봤다. 서비스 토큰은 주인이 허용 목록에 켜져 있어야 통하므로 문서와 토큰의 주인은 `people.ts` 가 허용 목록에 더한
 * aunt 다.
 */
import { randomUUID } from "node:crypto";
import { NEW_PERSON } from "./people.ts";
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type DocumentView = {
  id: number;
  collection: string;
  documentKey: string;
  revision: number;
  sensitive: boolean;
  content: string;
};

type MemoryView = { id: number };

type IssuedToken = { token: string; info: { id: number; expiresAt: string } };

type TokenView = { id: number; revokedAt: string | null };

type PersonView = { id: number; email: string };

type ServiceDocument = { content: string; revision: number };

const FIRST = "평문-표식-7391";
const SECOND = "평문-표식-8802";

export const memoryDocumentScenario: Scenario = {
  name: "Memory 문서와 서비스 토큰",

  async run(context) {
    const documentKey = `application-profile-${randomUUID().slice(0, 8)}`;
    const servicePath = `/service/memory-documents/identity/${documentKey}`;
    const aunt = context.tokens.aunt;
    const dad = context.tokens.dad;

    const readAs = (token: string | undefined, path = servicePath) => call(context, path, { token });
    const issue = async (token: string, allowSensitive: boolean) =>
      expectStatus(
        await call(context, "/service-tokens", {
          method: "POST",
          token,
          body: {
            label: "e2e",
            expiresInDays: 90,
            collections: [{ collection: "identity", allowSensitive }],
          },
        }),
        200,
        "서비스 토큰 발급",
      ).json<IssuedToken>();

    step("collection 목록에 identity 가 있다");
    const collections = expectStatus(
      await call(context, "/memory-collections", { token: aunt }),
      200,
      "collection 목록",
    ).json<{ key: string }[]>();
    expect(collections.some((item) => item.key === "identity"), "collection 목록에 identity 가 없다");

    step("민감 문서를 만든다");
    const created = expectStatus(
      await call(context, "/memory-documents", {
        method: "POST",
        token: aunt,
        body: {
          collection: "identity",
          documentKey,
          title: "지원서 공통 프로필",
          content: FIRST,
          sensitive: true,
        },
      }),
      200,
      "문서 만들기",
    ).json<DocumentView>();
    expect(created.revision === 1 && created.sensitive, `만든 문서의 판이나 민감 표시가 다르다: ${JSON.stringify(created)}`);

    step("민감 표시가 빠진 요청은 거절한다");
    expectStatus(
      await call(context, "/memory-documents", {
        method: "POST",
        token: aunt,
        body: { collection: "identity", documentKey: "no-sensitive-flag", title: "t", content: FIRST },
      }),
      400,
      "민감 표시가 없는 문서 만들기",
    );

    step("같은 이름으로 다시 만들지 못한다");
    const duplicate = expectStatus(
      await call(context, "/memory-documents", {
        method: "POST",
        token: aunt,
        body: { collection: "identity", documentKey, title: "t", content: FIRST, sensitive: true },
      }),
      409,
      "같은 이름의 문서",
    );
    expect(
      duplicate.json<{ code: string }>().code === "MEMORY_DOCUMENT_EXISTS",
      `겹친 문서의 오류 코드가 다르다: ${duplicate.body}`,
    );

    step("문서는 Memory 목록에 없다");
    const memories = expectStatus(await call(context, "/memories", { token: aunt }), 200, "Memory 목록").json<
      MemoryView[]
    >();
    expect(memories.every((memory) => memory.id !== created.id), "문서가 Memory 목록에 나온다");

    step("다른 사용자는 읽지 못한다");
    expectStatus(await call(context, `/memory-documents/${created.id}`, { token: context.tokens.kid }), 404, "남의 문서");

    step("만료 없이는 발급하지 못한다");
    const noExpiry = expectStatus(
      await call(context, "/service-tokens", {
        method: "POST",
        token: aunt,
        body: { label: "e2e", collections: [{ collection: "identity", allowSensitive: true }] },
      }),
      400,
      "만료 없는 발급",
    );
    expect(
      noExpiry.json<{ code: string }>().code === "VALIDATION_FAILED",
      `만료 없는 발급의 오류 코드가 다르다: ${noExpiry.body}`,
    );

    step("토큰을 발급한다");
    const issued = await issue(aunt, true);
    expect(issued.token.startsWith("fos_svc_"), "서비스 토큰의 접두사가 다르다");
    expect(issued.info.expiresAt.length > 0, "발급 응답에 만료 시각이 없다");

    step("토큰 목록에 원문이 없다");
    const listed = expectStatus(await call(context, "/service-tokens", { token: aunt }), 200, "토큰 목록");
    expect(!listed.body.includes(issued.token), "토큰 목록이 원문을 담는다");

    step("토큰으로 본문과 판 번호를 읽는다");
    const first = expectStatus(await readAs(issued.token), 200, "서비스 읽기").json<ServiceDocument>();
    expect(first.content === FIRST && first.revision === 1, `읽은 본문이나 판이 다르다: ${JSON.stringify(first)}`);

    step("고친 뒤 판 번호가 오른다");
    expectStatus(
      await call(context, `/memory-documents/${created.id}`, {
        method: "PUT",
        token: aunt,
        body: { content: SECOND, sensitive: true, expectedRevision: 1 },
      }),
      200,
      "문서 고치기",
    );
    const second = expectStatus(await readAs(issued.token), 200, "고친 뒤 읽기").json<ServiceDocument>();
    expect(second.content === SECOND && second.revision === 2, `고친 글이나 판이 다르다: ${JSON.stringify(second)}`);

    step("낡은 판 번호는 거절한다");
    const stale = expectStatus(
      await call(context, `/memory-documents/${created.id}`, {
        method: "PUT",
        token: aunt,
        body: { content: "x", sensitive: true, expectedRevision: 1 },
      }),
      409,
      "낡은 판 번호",
    );
    expect(
      stale.json<{ code: string }>().code === "MEMORY_REVISION_CONFLICT",
      `낡은 판의 오류 코드가 다르다: ${stale.body}`,
    );

    step("민감 허용이 없는 토큰은 읽지 못한다");
    const noSensitive = await issue(aunt, false);
    expectStatus(await readAs(noSensitive.token), 404, "민감 허용 없는 읽기");

    step("토큰 없이는 읽지 못한다");
    const anonymous = expectStatus(await readAs(undefined), 401, "토큰 없는 읽기");
    expect(anonymous.body.length === 0, `401 에 본문이 있다: ${anonymous.body}`);

    step("서비스 토큰은 사용자 API 를 열지 못한다");
    expectStatus(await call(context, "/memories", { token: issued.token }), 403, "서비스 토큰으로 사용자 API");

    step("폐기한 토큰은 읽지 못한다");
    const revocable = await issue(aunt, true);
    expectStatus(await readAs(revocable.token), 200, "폐기 전 읽기");
    expectStatus(
      await call(context, `/service-tokens/${revocable.info.id}`, { method: "DELETE", token: aunt }),
      200,
      "토큰 폐기",
    );
    expectStatus(await readAs(revocable.token), 401, "폐기한 토큰 읽기");

    step("허용 목록에 없는 사용자는 토큰을 발급받지 못한다");
    expectStatus(
      await call(context, "/service-tokens", {
        method: "POST",
        token: dad,
        body: { label: "e2e", expiresInDays: 90, collections: [{ collection: "identity", allowSensitive: true }] },
      }),
      403,
      "허용 목록에 없는 사용자의 발급",
    );

    step("사용자를 끄면 그 토큰이 죽는다");
    const live = await issue(aunt, true);
    expectStatus(await readAs(live.token), 200, "끄기 전 읽기");
    const people = expectStatus(await call(context, "/admin/people", { token: dad }), 200, "사람 목록").json<
      PersonView[]
    >();
    const person = people.find((item) => item.email === NEW_PERSON.email);
    expect(person !== undefined, "더한 사람이 목록에 없다");
    expectStatus(
      await call(context, `/admin/people/${person!.id}`, {
        method: "PATCH",
        token: dad,
        body: { enabled: false },
      }),
      200,
      "사용자 끄기",
    );
    const off = expectStatus(await readAs(live.token), 401, "끈 뒤 읽기");
    expect(off.body.length === 0, `끈 뒤 401 에 본문이 있다: ${off.body}`);

    step("끈 사용자는 살아 있는 세션으로도 새 토큰을 받지 못한다");
    expectStatus(
      await call(context, "/service-tokens", {
        method: "POST",
        token: aunt,
        body: { label: "e2e", expiresInDays: 90, collections: [{ collection: "identity", allowSensitive: true }] },
      }),
      403,
      "끈 사용자의 발급",
    );

    step("다시 켜도 되살아나지 않는다");
    expectStatus(
      await call(context, `/admin/people/${person!.id}`, {
        method: "PATCH",
        token: dad,
        body: { enabled: true },
      }),
      200,
      "사용자 켜기",
    );
    expectStatus(await readAs(live.token), 401, "다시 켠 뒤 읽기");
    const afterTokens = expectStatus(await call(context, "/service-tokens", { token: aunt }), 200, "켠 뒤 토큰 목록").json<
      TokenView[]
    >();
    const revoked = afterTokens.find((item) => item.id === live.info.id);
    expect(revoked?.revokedAt != null, "끌 때 그 토큰이 폐기되지 않았다");

    step("뒤 시나리오에 남기지 않는다");
    expectStatus(await call(context, `/memories/${created.id}`, { method: "DELETE", token: aunt }), 200, "문서 지우기");
  },
};
