/**
 * 원고 snapshot 의 preview, 저장 요청, 승인, job 조회를 실제 Control Plane 과 fake Hermes 의 private snapshot 경로로 왕복한다.
 *
 * <p>원고는 REST 로 만들고, MCP 는 붙잡아 둔 실행의 session 으로 서명해 부른다. 시작은 job 마다 한 번 이하여야 하고, 응답 유실·시작 전
 * 거절·재기동·재승인·재조회가 시작을 늘리지 않아야 한다. 계약은 `docs/features/content-draft.md` 의 「승인 snapshot과 저장 job」 이 갖는다.
 */
import { call, expect, expectStatus, fail, step, upload, type Context, type Response, type Scenario } from "../harness.ts";
import { callTool, contextFor, within, type ToolResult } from "../delegation-support.ts";
import { ConnectorSetup } from "../connector-support.ts";
import { SNAPSHOT_CONNECTOR } from "../fake-hermes.ts";
import { onePixelPng } from "./chat-attachment.ts";
import { NEW_PERSON } from "./people.ts";

const PREVIEW = "content_draft_preview";
const SAVE = "content_draft_save";
/** 원고 본문에 심는 표식이다. 성공한 preview 밖의 어느 오류 응답에도 나오면 안 된다. */
const MARKER = "SYNTHETIC_SNAPSHOT_E2E_PRIVATE_5521";

type Snapshot = {
  snapshotId: string; draftId: string; revision: number; snapshotHash: string;
  preview: { title: string }; assetIds: string[]; omittedAssetIds: string[]; expiresAt: string;
};
type SaveView = { snapshotId: string; actionId: string; jobId: string | null; status: string };
type JobView = { snapshotId: string; draftId: string; actionId: string; jobId: string | null; status: string; errorCode: string | null };
type ActionView = { actionId: string; status: string; contentDraft: { snapshotId: string } | null };
type Session = { token: string; session: string };

function unwrap<T>(result: ToolResult, what: string): T {
  expect(!result.isError, `${what} 가 실패했다: ${result.text.slice(0, 200)}`);
  const begin = "\n<external-data>\n";
  expect(result.text.includes(begin) && result.text.endsWith("</external-data>"), `${what} 에 외부 데이터 경계가 없다`);
  return JSON.parse(result.text.slice(result.text.indexOf(begin) + begin.length, result.text.lastIndexOf("</external-data>"))) as T;
}

export const contentSnapshotSaveScenario: Scenario = {
  name: "원고 snapshot 저장 승인과 job 조회",
  async run(context) {
    const owner = context.tokens.aunt;
    const setup = new ConnectorSetup(context, owner);
    /** 본문 표식이 나오면 안 되는 오류 응답 본문들이다. */
    const errors: string[] = [];
    const turns: Promise<unknown>[] = [];
    let tokenId: number | undefined;
    let failed = true;
    try {
      step("snapshot 커넥터를 연결하고 비공개 에이전트에 붙인다");
      const agent = await setup.attachedAgent("원고 저장 검사", [SNAPSHOT_CONNECTOR.id]);
      const issued = expectStatus(await call(context, "/admin/agent-tokens", { method: "POST", token: context.tokens.dad,
        body: { profileName: agent.profile, label: "snapshot-e2e" } }), 200, "MCP 토큰 발급").json<{ id: number; token: string }>();
      tokenId = issued.id;
      const conversationId = expectStatus(await call(context, "/chat/conversations", { method: "POST", token: owner,
        body: { agentCode: agent.code } }), 200, "대화 시작").json<{ conversationId: string }>().conversationId;

      step("합성 사진 두 장을 보내 한 장만 원고에 쓴다");
      const photo = async (name: string) => expectStatus(await upload(context, `/chat/conversations/${conversationId}/attachments`, {
        token: owner, field: "file", fileName: name, contentType: "image/png", bytes: onePixelPng(),
      }), 200, "사진 올리기").json<{ id: number }>().id;
      const used = await photo("used.png");
      const unused = await photo("unused.png");
      expectStatus(await call(context, "/chat/messages", { method: "POST", token: owner,
        body: { conversationId, text: "사진 두 장", attachmentIds: [used, unused] } }), 200, "사진 보내기");

      const base = `/chat/conversations/${conversationId}/content-drafts`;
      const create = async (title: string, withPhoto = false) => expectStatus(await call(context, base, { method: "POST", token: owner,
        body: { title, category: "분류", tags: [], blocks: [
          { type: "paragraph", text: `${MARKER} 본문` },
          ...(withPhoto ? [{ type: "image", assetId: String(used), caption: "합성 사진" }] : []),
        ] } }), 201, "원고 만들기").json<{ draft: { draftId: string; revision: number } }>().draft;
      const drafts = {
        accepted: await create(`${MARKER} 접수`),
        lost: await create(`${MARKER} 유실`),
        refused: await create(`${MARKER} 거절`),
        photo: await create(`${MARKER} 사진`, true),
      };

      const hold = async (text: string): Promise<Session> => {
        context.hermes.holdNextRun();
        const turn = call(context, "/chat/messages", { method: "POST", token: owner, body: { conversationId, text, agentCode: agent.code } });
        turn.catch(() => undefined);
        turns.push(turn);
        await within(context.hermes.waitForHeldRun(), 5_000, "원고 실행을 받지 못했다");
        const session = context.hermes.heldRun()?.sessionId;
        if (session === undefined) fail("붙잡은 실행의 session 이 없다");
        return { token: issued.token, session };
      };
      const tool = (held: Session, name: string, args: Record<string, unknown>) =>
        callTool(context, held.token, name, args, contextFor(held.token, name, held.session));
      const preview = async (held: Session, draft: { draftId: string; revision: number }, bindingId?: number) =>
        unwrap<Snapshot>(await tool(held, PREVIEW, { draftId: draft.draftId, revision: draft.revision,
          ...(bindingId === undefined ? {} : { bindingId }) }), "preview");
      const save = async (held: Session, snapshotId: string) => unwrap<SaveView>(await tool(held, SAVE, { snapshotId }), "저장 요청");
      const approve = async (actionId: string): Promise<Response> => {
        const response = await call(context, `/connector-actions/${actionId}/approve`, { method: "POST", token: owner });
        if (response.status !== 200) errors.push(response.body);
        return response;
      };
      const jobPath = (snapshot: Snapshot) => `${base}/${snapshot.draftId}/snapshots/${snapshot.snapshotId}/job`;
      const job = async (snapshot: Snapshot) => expectStatus(await call(context, jobPath(snapshot), { token: owner }), 200, "job 조회")
        .json<JobView>();
      const starts = () => context.hermes.snapshotStarts();
      const totalStarts = () => Object.values(starts()).reduce((sum, count) => sum + count, 0);
      const statusCalls = () => context.hermes.snapshotRequests().filter((line) => line.operation === "status").length;

      let held = await hold("원고 저장 검사");

      step("bindingId 를 생략한 preview 는 하나뿐인 snapshot 바인딩을 쓴다");
      const accepted = await preview(held, drafts.accepted);
      expect(accepted.draftId === drafts.accepted.draftId && accepted.revision === 1 && /^[0-9a-f]{64}$/.test(accepted.snapshotHash)
        && accepted.assetIds.length === 0 && accepted.omittedAssetIds.includes(String(used))
        && accepted.omittedAssetIds.includes(String(unused)), `preview 가 다르다: ${JSON.stringify({ ...accepted, preview: undefined })}`);
      expect(context.hermes.snapshotRequests().some((line) => line.operation === "compile" && line.status === 200),
        "preview 가 대역 compiler 를 거치지 않았다");
      const again = await preview(held, drafts.accepted);
      expect(again.snapshotId === accepted.snapshotId, "같은 판의 반복 preview 가 새 snapshot 을 만들었다");

      step("모르는 판의 preview 오류에 본문이 없다");
      const missing = await tool(held, PREVIEW, { draftId: drafts.accepted.draftId, revision: 99 });
      expect(missing.isError, "없는 판의 preview 가 성공했다");
      errors.push(missing.text);

      step("같은 snapshot 의 저장 요청을 동시에 세 번 보내면 승인 줄 하나다");
      const saves = await Promise.all([1, 2, 3].map(() => save(held, accepted.snapshotId)));
      expect(new Set(saves.map((view) => view.actionId)).size === 1 && saves.every((view) => view.jobId === null
        && view.status === "PREVIEW_READY"), `동시 저장 요청이 하나로 묶이지 않았다: ${JSON.stringify(saves)}`);
      const actionA = saves[0]!.actionId;
      expect((await job(accepted)).status === "PREVIEW_READY" && totalStarts() === 0, "승인 전에 시작했다");

      step("승인하면 시작을 한 번 보내고 job 은 SAVING 이다");
      const approvedA = expectStatus(await approve(actionA), 200, "승인").json<ActionView>();
      expect(approvedA.status === "EXECUTING", `승인한 줄이 EXECUTING 이 아니다: ${approvedA.status}`);
      const jobA = await job(accepted);
      expect(jobA.status === "SAVING" && jobA.jobId !== null && starts()[jobA.jobId] === 1 && totalStarts() === 1,
        `시작 뒤 job 이 다르다: ${JSON.stringify({ jobA, starts: starts() })}`);
      const startLine = context.hermes.snapshotRequests().find((line) => line.operation === "start" && line.jobId === jobA.jobId);
      if (startLine?.bindingId == null) fail("시작 요청의 stamp 에 바인딩이 없다");
      const bindingId = Number(startLine.bindingId);

      step("다시 승인해도 시작이 늘지 않는다");
      const reapproved = await approve(actionA);
      expect(reapproved.status === 409 && reapproved.json<{ code: string }>().code === "CONNECTOR_ACTION_NOT_PENDING",
        `재승인이 거절되지 않았다: ${reapproved.status}`);
      expect(totalStarts() === 1, "재승인이 시작을 다시 보냈다");

      step("시작 응답을 잃으면 UNKNOWN 이고 재승인과 재조회가 시작을 늘리지 않는다");
      const lost = await preview(held, drafts.lost, bindingId);
      const actionB = (await save(held, lost.snapshotId)).actionId;
      context.hermes.loseNextSnapshotStart("disconnect");
      const approvedB = expectStatus(await approve(actionB), 200, "응답 유실 승인").json<ActionView>();
      const jobB = await job(lost);
      expect(approvedB.status === "UNKNOWN" && jobB.status === "UNKNOWN" && jobB.jobId !== null,
        `응답 유실 뒤 상태가 UNKNOWN 이 아니다: ${approvedB.status} ${jobB.status}`);
      expect((await approve(actionB)).status === 409, "UNKNOWN 줄의 재승인이 거절되지 않았다");
      expect((await job(lost)).status === "UNKNOWN", "재조회가 UNKNOWN 을 바꿨다");
      expect(starts()[jobB.jobId!] === 1 && totalStarts() === 2, `응답 유실 뒤 시작 수가 다르다: ${JSON.stringify(starts())}`);

      step("시작 전 거절이면 FAILED 다");
      const refused = await preview(held, drafts.refused, bindingId);
      const actionC = (await save(held, refused.snapshotId)).actionId;
      context.hermes.rejectNextSnapshotStart("busy");
      const approvedC = expectStatus(await approve(actionC), 200, "거절될 승인").json<ActionView>();
      const jobC = await job(refused);
      expect(approvedC.status === "FAILED" && jobC.status === "FAILED" && jobC.errorCode === "busy",
        `시작 전 거절이 FAILED 가 아니다: ${approvedC.status} ${JSON.stringify(jobC)}`);
      expect(starts()[jobC.jobId!] === 1 && totalStarts() === 3, "거절된 시작 수가 다르다");

      step("사진 원고는 쓴 사진과 빠진 사진을 나눠 보이고 succeeded 를 받으면 SUCCEEDED 로 남는다");
      const photoSnapshot = await preview(held, drafts.photo);
      expect(photoSnapshot.assetIds.join() === String(used) && photoSnapshot.omittedAssetIds.join() === String(unused),
        `사진 preview 의 포함·빠진 ID 가 다르다: ${photoSnapshot.assetIds} / ${photoSnapshot.omittedAssetIds}`);
      const actionE = (await save(held, photoSnapshot.snapshotId)).actionId;
      expectStatus(await approve(actionE), 200, "사진 원고 승인");
      const jobE = await job(photoSnapshot);
      expect(jobE.status === "SAVING", `사진 원고 job 이 SAVING 이 아니다: ${jobE.status}`);
      context.hermes.setSnapshotJobOutcome(jobE.jobId!, "succeeded");
      expect((await job(photoSnapshot)).status === "SUCCEEDED", "succeeded 를 받고도 SUCCEEDED 가 아니다");
      context.hermes.setSnapshotJobOutcome(jobE.jobId!, "failed");
      const callsAfterTerminal = statusCalls();
      expect((await job(photoSnapshot)).status === "SUCCEEDED" && statusCalls() === callsAfterTerminal,
        "끝난 job 을 다시 조회하거나 상태를 덮었다");

      context.hermes.releaseHeldRun();
      await within(Promise.all(turns), 10_000, "원고 실행이 끝나지 않았다");

      step("EXECUTING 동안 바인딩 해제는 CONNECTOR_ACTION_EXECUTING 으로 거절된다");
      const unbind = await call(context, `/agents/${agent.code}/connections/${SNAPSHOT_CONNECTOR.id}`, { method: "DELETE", token: owner });
      errors.push(unbind.body);
      expect(unbind.status === 409 && unbind.json<{ code: string }>().code === "CONNECTOR_ACTION_EXECUTING",
        `EXECUTING 중 해제가 거절되지 않았다: ${unbind.status}`);

      step("Control Plane 을 다시 띄워도 시작을 다시 보내지 않고 job 은 SAVING 이다");
      await context.restartControlPlane();
      const afterRestart = await job(accepted);
      const actions = expectStatus(await call(context, `/chat/conversations/${conversationId}/connector-actions`, { token: owner }),
        200, "승인 줄 목록").json<ActionView[]>();
      expect(afterRestart.status === "SAVING" && actions.find((action) => action.actionId === actionA)?.status === "EXECUTING",
        `재기동 뒤 상태가 바뀌었다: ${afterRestart.status}`);
      expect(totalStarts() === 4, `재기동 뒤 시작 수가 다르다: ${JSON.stringify(starts())}`);
      context.hermes.setSnapshotJobOutcome(jobA.jobId!, "succeeded");
      expect((await job(accepted)).status === "SUCCEEDED", "재기동 뒤 succeeded 가 반영되지 않았다");

      step("원고를 고치면 옛 snapshot 은 409 STALE 이고 남의 사용자는 404 다");
      expectStatus(await call(context, `${base}/${drafts.accepted.draftId}`, { method: "PUT", token: owner,
        body: { expectedRevision: 1, draft: { title: "고친 원고", category: "분류", tags: [], blocks: [] } } }), 200, "원고 고치기");
      const stale = await call(context, `${base}/${accepted.draftId}/snapshots/${accepted.snapshotId}`, { token: owner });
      errors.push(stale.body);
      expect(stale.status === 409 && stale.json<{ code: string }>().code === "CONTENT_DRAFT_STALE", `옛 snapshot 이 STALE 이 아니다: ${stale.status}`);
      const current = expectStatus(await call(context, `${base}/${photoSnapshot.draftId}/snapshots/${photoSnapshot.snapshotId}`, { token: owner }),
        200, "고치지 않은 snapshot").json<Snapshot>();
      expect(current.snapshotHash === photoSnapshot.snapshotHash, "고치지 않은 snapshot 의 지문이 달라졌다");
      for (const path of [`${base}/${photoSnapshot.draftId}/snapshots/${photoSnapshot.snapshotId}`, jobPath(photoSnapshot),
        `/chat/conversations/${conversationId}/content-draft-jobs`]) {
        const foreign = await call(context, path, { token: context.tokens.kid });
        errors.push(foreign.body);
        expect(foreign.status === 404, `남의 사용자가 ${path} 를 ${foreign.status} 로 받았다`);
      }
      const listed = expectStatus(await call(context, `/chat/conversations/${conversationId}/content-draft-jobs`, { token: owner }),
        200, "job 목록").json<{ items: JobView[]; nextBeforeSnapshotId: string | null }>();
      expect(listed.items.length === 4 && listed.nextBeforeSnapshotId === null
        && listed.items.find((item) => item.snapshotId === lost.snapshotId)?.status === "UNKNOWN",
        `job 목록이 다르다: ${JSON.stringify(listed.items.map((item) => item.status))}`);

      step("사용자 권한을 철회하면 승인이 거절되고 시작하지 않는다");
      held = await hold("철회 검사");
      const revoked = await preview(held, await create(`${MARKER} 철회`));
      const actionD = (await save(held, revoked.snapshotId)).actionId;
      context.hermes.releaseHeldRun();
      await within(Promise.all(turns), 10_000, "철회 검사 실행이 끝나지 않았다");
      const person = expectStatus(await call(context, "/admin/people", { token: context.tokens.dad }), 200, "사람 목록")
        .json<{ id: number; email: string }[]>().find((entry) => entry.email === NEW_PERSON.email);
      if (person === undefined) fail("철회할 사람이 없다");
      const toggle = async (enabled: boolean) => expectStatus(await call(context, `/admin/people/${person.id}`, {
        method: "PATCH", token: context.tokens.dad, body: { enabled } }), 200, enabled ? "다시 허용" : "사용 중지");
      await toggle(false);
      try {
        const denied = await approve(actionD);
        expect(denied.status === 401 && denied.json<{ code: string }>().code === "ACCESS_REVOKED", `철회 뒤 승인이 거절되지 않았다: ${denied.status}`);
      } finally {
        await toggle(true);
      }
      expect(totalStarts() === 4, "철회 뒤 승인이 시작을 보냈다");
      expectStatus(await call(context, `/connector-actions/${actionD}/reject`, { method: "POST", token: owner }), 200, "남은 줄 거절");

      step("job 마다 시작은 한 번 이하이고 본문 표식이 오류 응답과 대역 오류에 없다");
      expect(Object.values(starts()).every((count) => count <= 1), `한 job 을 두 번 시작했다: ${JSON.stringify(starts())}`);
      expect(context.hermes.snapshotRequests().every((line) => line.bearer && !line.cookie), "서비스 Bearer 없이 snapshot 경로를 불렀다");
      const cookieOnly = await fetch(`${context.hermesBaseUrl}/api/connectors/${SNAPSHOT_CONNECTOR.id}/snapshot`, {
        method: "POST", headers: { Cookie: "session=dashboard", "Content-Type": "application/json" },
        body: JSON.stringify({ profile: agent.profile, operation: "status", args: {} }),
      });
      expect(cookieOnly.status === 401, `대시보드 쿠키만 있는 snapshot 요청이 열렸다: ${cookieOnly.status}`);
      const leaks = [...errors, ...context.hermes.snapshotErrorBodies()].filter((body) => body.includes(MARKER));
      expect(leaks.length === 0, "본문 표식이 오류 응답에 나왔다");
      expect(!(await context.readBackendLog()).includes(MARKER), "본문 표식이 Control Plane 로그에 나왔다");
      step(`시작 수: ${JSON.stringify({ total: totalStarts(), jobs: Object.keys(starts()).length })}`);
      failed = false;
    } finally {
      context.hermes.releaseHeldRun();
      await within(Promise.all(turns), 10_000, "원고 실행 정리 실패").catch(() => undefined);
      if (tokenId !== undefined) {
        const revoked = await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad });
        if (!failed) expectStatus(revoked, 200, "MCP 토큰 폐기");
      }
      await setup.cleanUp(failed);
    }
  },
};
