"use client";

import { useEffect, useState } from "react";
import { GroupTierSettings } from "@/components/admin/group-tier-settings";
import { ModelHiddenForm } from "@/components/admin/model-hidden-form";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { formatFullTime } from "@/lib/format";
import {
  getGroupModelCatalog,
  getHiddenModelCandidates,
  getHiddenModelSettings,
  refreshGroupModelCatalog,
  type CatalogRefreshStatus,
  type GroupModelCatalog,
  type HiddenModelSettings,
} from "@/lib/model-settings";

type Settings =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded"; settings: HiddenModelSettings };

/**
 * 「목록 새로 읽기」 를 누른 결과다. `reloadFailed` 는 Hermes 에는 다시 물었지만 그 뒤 목록 조회가 실패해 보이던
 * 선택지를 그대로 둔 경우다.
 */
type RefreshNotice = {
  status: CatalogRefreshStatus;
  refreshedAt: string | null;
  reloadFailed: boolean;
};

const REFRESH_TEXT: Record<CatalogRefreshStatus, string> = {
  REFRESHED: "모델 목록을 다시 읽었어요.",
  // 앞 시도가 아직 도는 중일 수도 있어 「방금 읽었다」 고 말하지 않는다.
  REUSED: "30초 안에는 새로 묻지 않아요. 잠시 뒤 다시 눌러 주세요.",
  FAILED: "모델 목록을 다시 읽지 못했어요. 잠시 뒤 다시 눌러 주세요.",
};

/** 「목록 새로 읽기」 단추와 그 결과 안내다. */
function CatalogRefreshSection({
  refreshing,
  notice,
  onRefresh,
}: {
  refreshing: boolean;
  notice: RefreshNotice | null;
  onRefresh(): void;
}) {
  return (
    <section aria-label="모델 목록 새로 읽기" className="grid gap-2">
      <p className="text-sm text-muted-foreground">
        새로 나온 모델이 아래 목록에 없으면 목록을 다시 읽어 보세요.
      </p>
      <div>
        <Button
          type="button"
          variant="outline"
          data-testid="model-catalog-refresh"
          loading={refreshing}
          loadingText="읽는 중"
          onClick={onRefresh}
        >
          목록 새로 읽기
        </Button>
      </div>
      {notice !== null ? (
        <div data-testid="model-catalog-refresh-notice" className="grid gap-1">
          <Notice
            variant={
              notice.status === "FAILED"
                ? "error"
                : notice.status === "REUSED"
                  ? "warning"
                  : "success"
            }
            role={notice.status === "FAILED" ? "alert" : undefined}
          >
            {REFRESH_TEXT[notice.status]}
          </Notice>
          {notice.reloadFailed ? (
            <Notice variant="error" role="alert">
              다시 읽은 목록을 불러오지 못했어요. 보이던 목록을 그대로 둬요.
            </Notice>
          ) : null}
          {notice.refreshedAt !== null ? (
            <p
              data-testid="model-catalog-refreshed-at"
              className="text-xs text-muted-foreground"
            >
              마지막으로 다시 읽은 시각 {formatFullTime(notice.refreshedAt)}
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

/**
 * 그룹 전체에 걸리는 모델 설정이다. 에이전트는 모델을 갖지 않으므로 에이전트를 고르는 칸이 없다. 숨길 모델의 후보는
 * 켜진 모든 에이전트 profile 목록의 합집합이다.
 *
 * <p>「목록 새로 읽기」 는 두 절 위에 하나만 둔다. 같은 다시 읽기가 단계별 모델의 선택지와 숨길 후보를 함께 바꾼다.
 */
export function ModelAdminPanel() {
  const [settings, setSettings] = useState<Settings>({ status: "loading" });
  const [refreshing, setRefreshing] = useState(false);
  const [notice, setNotice] = useState<RefreshNotice | null>(null);
  /** 다시 읽은 뒤의 그룹 목록이다. 아직 다시 읽지 않았으면 null 이고 단계 설정이 처음 읽은 목록을 쓴다 */
  const [tierCatalog, setTierCatalog] = useState<GroupModelCatalog | null>(
    null,
  );

  useEffect(() => {
    let active = true;
    void getHiddenModelSettings().then((result) => {
      if (!active) return;
      setSettings(
        result.ok
          ? { status: "loaded", settings: result.data }
          : { status: "failed", message: result.message },
      );
    });
    return () => {
      active = false;
    };
  }, []);

  async function refresh() {
    setRefreshing(true);
    setNotice(null);
    const result = await refreshGroupModelCatalog();
    if (result.status === "FAILED") {
      // 다시 읽지 못했으면 목록 조회도 다시 하지 않는다. 보이던 선택지를 그대로 둔다.
      setNotice({ ...result, reloadFailed: false });
      setRefreshing(false);
      return;
    }
    const [catalog, candidates] = await Promise.all([
      getGroupModelCatalog(),
      getHiddenModelCandidates(),
    ]);
    if (catalog !== null) setTierCatalog(catalog);
    if (candidates !== null) {
      // 후보만 바꾼다. 숨김 폼을 다시 만들지 않아 저장 전에 고친 체크가 남는다.
      setSettings((current) =>
        current.status === "loaded"
          ? {
              status: "loaded",
              settings: {
                ...current.settings,
                catalog: candidates,
                catalogMissing: false,
              },
            }
          : current,
      );
    }
    setNotice({
      ...result,
      reloadFailed: catalog === null || candidates === null,
    });
    setRefreshing(false);
  }

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold">모델</h1>
      <div className="grid gap-6">
        <p className="text-sm text-muted-foreground">
          그룹 모델 설정과 모델 숨김은 그룹의 모든 에이전트에 걸려요.
        </p>
        <CatalogRefreshSection
          refreshing={refreshing}
          notice={notice}
          onRefresh={() => void refresh()}
        />
        <section
          aria-label="그룹 모델 설정"
          className="rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">그룹 모델 설정</h2>
          <GroupTierSettings refreshedCatalog={tierCatalog} />
        </section>
        <section
          aria-label="모델 숨김"
          className="grid gap-4 rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">모델 숨김</h2>
          {settings.status === "loading" ? (
            <p className="text-sm text-muted-foreground">
              모델 목록을 불러오고 있어요.
            </p>
          ) : settings.status === "failed" ? (
            <Notice variant="error" role="alert">
              모델 설정을 불러오지 못했어요. {settings.message}
            </Notice>
          ) : (
            <>
              {settings.settings.catalogMissing ? (
                <Notice
                  variant="warning"
                  data-testid="model-hidden-catalog-missing"
                >
                  모델 목록을 불러오지 못했어요. 지금은 저장된 숨김을 푸는 것만
                  할 수 있어요.
                </Notice>
              ) : null}
              <ModelHiddenForm
                showTitle={false}
                settings={settings.settings}
                onSaved={(entries) =>
                  setSettings({
                    status: "loaded",
                    settings: { ...settings.settings, hidden: { entries } },
                  })
                }
              />
            </>
          )}
        </section>
      </div>
    </div>
  );
}
