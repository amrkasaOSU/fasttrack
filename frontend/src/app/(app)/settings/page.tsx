"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { CsvTools } from "@/components/csv-tools";
import { NotConfiguredNotice } from "@/components/ui/states";
import { useCurrentUser, useFeatureConfig } from "@/lib/session";

export default function SettingsPage() {
  const me = useCurrentUser();
  const config = useFeatureConfig();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-sm text-fg-muted">Your account, your data, and what this deployment can do.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Account</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          <p>{me.data?.displayName}</p>
          <p className="text-fg-muted">{me.data?.email}</p>
          {me.data?.demo && (
            <p className="text-fg-muted">
              This is the shared demo account. Its data is synthetic and resets with the
              deployment.
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Your data</CardTitle>
        </CardHeader>
        <CardContent>
          <CsvTools />
        </CardContent>
      </Card>

      {config.isSuccess && !config.data.gmailConfigured && (
        <NotConfiguredNotice
          feature="Gmail sync"
          description="This deployment has no Google credentials, so FastTrack cannot read email. Applications you add by hand or import from CSV work normally."
        />
      )}

      {config.isSuccess && !config.data.llmConfigured && (
        <NotConfiguredNotice
          feature="AI classification"
          description="No LLM key is configured. Email classification and resume parsing fall back to the built-in rule-based path when they run."
        />
      )}
    </div>
  );
}
