"use client";

import { useEffect, useState } from "react";
import { GroupTierSettings } from "@/components/admin/group-tier-settings";
import { ModelHiddenForm } from "@/components/admin/model-hidden-form";
import { Notice } from "@/components/ui/notice";
import {
  getHiddenModelSettings,
  type HiddenModelSettings,
} from "@/lib/model-settings";

type Settings =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded"; settings: HiddenModelSettings };

/**
 * 그룹 전체에 걸리는 모델 설정이다. 에이전트는 모델을 갖지 않으므로 에이전트를 고르는 칸이 없다. 숨길 모델의 후보는
 * 켜진 모든 에이전트 profile 목록의 합집합이다.
 */
export function ModelAdminPanel() {
  const [settings, setSettings] = useState<Settings>({ status: "loading" });

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

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold">모델</h1>
      <div className="grid gap-6">
        <p className="text-sm text-muted-foreground">
          그룹 모델 설정과 모델 숨김은 그룹의 모든 에이전트에 걸려요.
        </p>
        <section
          aria-label="그룹 모델 설정"
          className="rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">그룹 모델 설정</h2>
          <GroupTierSettings />
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
