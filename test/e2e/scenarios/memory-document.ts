/**
 * 사용자가 민감 문서를 만들고 자기 JWT로 본문과 판 번호를 읽는 흐름을 한 번 왕복한다.
 *
 * <p>보안·암호화 설정과 Flyway로 만든 스키마를 함께 확인한다. 문서의 주인은 `people.ts`가 허용 목록에 더한 aunt이며,
 * 다른 사용자와 인증 없는 요청을 거절하고 접근 해제 뒤 기존 JWT도 막히는지 본다.
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

type PersonView = { id: number; email: string };

const FIRST = "평문-표식-7391";
const SECOND = "평문-표식-8802";

export const memoryDocumentScenario: Scenario = {
  name: "Memory 문서와 사용자 인증",

  async run(context) {
    const documentKey = `application-profile-${randomUUID().slice(0, 8)}`;
    const aunt = context.tokens.aunt;
    const dad = context.tokens.dad;

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

    const readAs = (token: string | undefined) => call(context, `/memory-documents/${created.id}`, { token });
    step("사용자 JWT로 본문과 판 번호를 읽는다");
    const first = expectStatus(await readAs(aunt), 200, "사용자 문서 읽기").json<DocumentView>();
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
    const second = expectStatus(await readAs(aunt), 200, "고친 뒤 읽기").json<DocumentView>();
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

    step("토큰 없이는 읽지 못한다");
    expectStatus(await readAs(undefined), 403, "토큰 없는 읽기");

    step("사용자를 끄면 기존 JWT도 거절한다");
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
    const off = expectStatus(await readAs(aunt), 401, "끈 뒤 읽기");
    expect(
      off.json<{ code: string }>().code === "ACCESS_REVOKED",
      `끈 사용자의 문서 읽기가 ACCESS_REVOKED 가 아니다: ${off.body}`,
    );

    step("다시 켜면 사용자 JWT로 문서를 읽는다");
    expectStatus(
      await call(context, `/admin/people/${person!.id}`, {
        method: "PATCH",
        token: dad,
        body: { enabled: true },
      }),
      200,
      "사용자 켜기",
    );
    const restored = expectStatus(await readAs(aunt), 200, "다시 켠 뒤 읽기").json<DocumentView>();
    expect(restored.content === SECOND && restored.revision === 2, "접근 해제와 복구가 문서를 바꿨다");

    step("뒤 시나리오에 남기지 않는다");
    expectStatus(await call(context, `/memories/${created.id}`, { method: "DELETE", token: aunt }), 200, "문서 지우기");
  },
};
