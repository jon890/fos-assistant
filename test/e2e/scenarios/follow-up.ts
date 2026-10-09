/**
 * 사람이 할 일을 더하고 고치고 끝내는 경로와, 남의 할 일을 읽지도 끝내지도 못하는 것을 본다.
 *
 * <p>계약은 `backend/docs/flow.md` 의 「API(할 일)」 가 갖는다. turn 을 돌리지 않는다.
 * 바로 뒤의 사용량 시나리오가 실행 수를 앞의 대화 turn 수와 같은지 보기 때문이다.
 */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";

type FollowUp = {
  id: string;
  title: string;
  status: string;
  dueAt: string | null;
  waiting: boolean;
  conversationId: string | null;
  proposed: boolean;
  createdAt: string;
  acceptedAt: string | null;
  closedAt: string | null;
};
type ErrorBody = { code: string };

const TITLE = "할 일 검사 7391";
const DUE_AT = "2026-10-05T09:00:00Z";

export const followUpScenario: Scenario = {
  name: "할 일",

  async run(context) {
    const dad = context.tokens.dad;
    const kid = context.tokens.kid;

    step("아빠가 앞 시나리오의 대화에 할 일을 더하면 OPEN 이고 그 대화가 연결된다");
    const conversations = expectStatus(
      await call(context, "/chat/conversations", { token: dad }),
      200,
      "대화 목록",
    ).json<{ items: { id: string }[] }>().items;
    expect(conversations.length > 0, "연결할 대화가 없다");
    const conversationId = conversations[0]!.id;
    const created = expectStatus(
      await call(context, "/follow-ups", { method: "POST", token: dad, body: { title: TITLE, conversationId } }),
      200,
      "할 일 더하기",
    ).json<FollowUp>();
    expect(created.status === "OPEN", `더한 할 일이 OPEN 이 아니다: ${created.status}`);
    expect(
      created.conversationId === conversationId,
      `연결한 대화가 다르다: ${created.conversationId}, 기대 ${conversationId}`,
    );
    expect(!created.proposed, "사람이 더한 할 일이 제안으로 표시됐다");

    step("기한을 넣고, 제목만 보내면 기한이 그대로이고, 기한을 null 로 보내면 지워진다");
    const withDue = patch(
      await call(context, `/follow-ups/${created.id}`, { method: "PATCH", token: dad, body: { dueAt: DUE_AT } }),
      "기한 넣기",
    );
    expect(Date.parse(withDue.dueAt ?? "") === Date.parse(DUE_AT), `넣은 기한이 다르다: ${withDue.dueAt}`);
    const titleOnly = patch(
      await call(context, `/follow-ups/${created.id}`, {
        method: "PATCH",
        token: dad,
        body: { title: `${TITLE} 고침` },
      }),
      "제목만 고치기",
    );
    expect(titleOnly.title === `${TITLE} 고침`, `고친 제목이 다르다: ${titleOnly.title}`);
    expect(
      Date.parse(titleOnly.dueAt ?? "") === Date.parse(DUE_AT),
      `제목만 고쳤는데 기한이 바뀌었다: ${titleOnly.dueAt}`,
    );
    const cleared = patch(
      await call(context, `/follow-ups/${created.id}`, { method: "PATCH", token: dad, body: { dueAt: null } }),
      "기한 지우기",
    );
    expect(cleared.dueAt === null, `기한을 지웠는데 남았다: ${cleared.dueAt}`);

    step("기한을 읽지 못하거나 연도가 범위 밖이거나 제목이 공백뿐이거나 없으면 400 VALIDATION_FAILED 다");
    expectValidation(
      await call(context, "/follow-ups", { method: "POST", token: dad, body: { title: "할 일 검사 8402", dueAt: "내일" } }),
      "읽지 못하는 기한",
    );
    expectValidation(
      await call(context, "/follow-ups", {
        method: "POST",
        token: dad,
        body: { title: "할 일 검사 8403", dueAt: "+10000-01-01T00:00:00Z" },
      }),
      "연도가 9999 를 넘는 기한",
    );
    expectValidation(
      await call(context, "/follow-ups", { method: "POST", token: dad, body: { title: "   " } }),
      "공백뿐인 제목",
    );
    expectValidation(
      await call(context, "/follow-ups", { method: "POST", token: dad, body: { waiting: true } }),
      "제목 없는 본문",
    );

    step("아이의 목록에는 아빠의 할 일이 없고, 아이가 끝내려 하면 404 다");
    const kids = expectStatus(await call(context, "/follow-ups", { token: kid }), 200, "아이의 할 일 목록").json<
      FollowUp[]
    >();
    expect(!kids.some((each) => each.id === created.id), "아이의 목록에 아빠의 할 일이 있다");
    expectStatus(
      await call(context, `/follow-ups/${created.id}/done`, { method: "POST", token: kid }),
      404,
      "아이가 아빠의 할 일 끝내기",
    );

    step("아빠가 끝내면 200 이고 한 번 더 끝내면 409 다");
    const done = expectStatus(
      await call(context, `/follow-ups/${created.id}/done`, { method: "POST", token: dad }),
      200,
      "할 일 끝내기",
    ).json<FollowUp>();
    expect(done.status === "DONE", `끝낸 할 일이 DONE 이 아니다: ${done.status}`);
    expect(done.closedAt !== null, "끝낸 할 일에 끝낸 시각이 없다");
    const again = expectStatus(
      await call(context, `/follow-ups/${created.id}/done`, { method: "POST", token: dad }),
      409,
      "끝낸 할 일 다시 끝내기",
    ).json<ErrorBody>();
    expect(again.code === "FOLLOW_UP_STATE_CONFLICT", `다시 끝낸 오류 코드가 다르다: ${again.code}`);

    // 토큰 없는 요청은 다른 /api/v1 경로와 같이 보안 필터가 403 으로 막는다.
    step("토큰 없이 부르면 거절한다");
    expectStatus(await call(context, "/follow-ups"), 403, "토큰 없는 할 일 목록");
  },
};

function patch(response: Response, what: string): FollowUp {
  return expectStatus(response, 200, what).json<FollowUp>();
}

function expectValidation(response: Response, what: string): void {
  const body = expectStatus(response, 400, what).json<ErrorBody>();
  expect(body.code === "VALIDATION_FAILED", `${what}의 오류 코드가 다르다: ${body.code}`);
}
