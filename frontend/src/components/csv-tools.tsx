"use client";

import { useRef, useState } from "react";
import { API_BASE_URL } from "@/lib/api";
import { useImportCsv, type CsvImportResult } from "@/lib/applications";
import { Button } from "./ui/button";

/**
 * Import an existing tracker and export everything back out.
 *
 * The import result is shown in full - created, skipped duplicates, and every
 * row that could not be read, by line number - because a silent import that
 * drops rows is worse than no import at all.
 */
export function CsvTools() {
  const fileInput = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<CsvImportResult | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const importCsv = useImportCsv();

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        <input
          ref={fileInput}
          type="file"
          accept=".csv,text/csv"
          className="sr-only"
          aria-label="Choose a CSV file to import"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (!file) return;
            setFailure(null);
            setResult(null);
            importCsv.mutate(file, {
              onSuccess: setResult,
              onError: (error) =>
                setFailure(error instanceof Error ? error.message : "Import failed."),
            });
            event.target.value = "";
          }}
        />
        <Button
          variant="secondary"
          onClick={() => fileInput.current?.click()}
          disabled={importCsv.isPending}
        >
          {importCsv.isPending ? "Importing…" : "Import CSV"}
        </Button>
        <Button variant="secondary" asChild>
          <a href={`${API_BASE_URL}/api/applications/export`}>Export CSV</a>
        </Button>
      </div>

      <p className="text-xs text-fg-muted">
        Columns: Company, Position, Date Applied, Status, Location, Notes. Rows that
        already exist are skipped.
      </p>

      {failure && (
        <p role="alert" className="text-sm text-danger">
          {failure}
        </p>
      )}

      {result && (
        <div className="rounded-lg border border-border bg-surface-muted p-4 text-sm">
          <p className="font-medium">
            Imported {result.created} of {result.rowsRead} rows
            {result.duplicatesSkipped > 0 && ` · ${result.duplicatesSkipped} already existed`}
          </p>
          {result.warnings.length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5 text-fg-muted">
              {result.warnings.map((warning) => (
                <li key={`w-${warning.line}-${warning.message}`}>
                  Line {warning.line}: {warning.message}
                </li>
              ))}
            </ul>
          )}
          {result.errors.length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5 text-danger">
              {result.errors.map((error) => (
                <li key={`e-${error.line}-${error.message}`}>
                  Line {error.line}: {error.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
