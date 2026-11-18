"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { APPLICATION_STATUSES } from "@/lib/types";
import { statusLabel } from "@/lib/format";
import type { ApplicationSummary, UpsertApplicationInput } from "@/lib/applications";
import { Button } from "./ui/button";
import { FieldError, Input, Label, Select, Textarea } from "./ui/input";

const schema = z.object({
  companyName: z.string().trim().min(1, "Company is required").max(200),
  positionTitle: z.string().trim().min(1, "Position is required").max(200),
  location: z.string().trim().max(200).optional(),
  requisitionId: z.string().trim().max(100).optional(),
  atsPlatform: z.string().trim().max(80).optional(),
  status: z.enum(APPLICATION_STATUSES),
  appliedAt: z.string().optional(),
  notes: z.string().trim().max(4000).optional(),
});

export type ApplicationFormValues = z.infer<typeof schema>;

function toDateInput(value: string | null | undefined): string {
  if (!value) return new Date().toISOString().slice(0, 10);
  return new Date(value).toISOString().slice(0, 10);
}

/** Add or edit an application. Validation mirrors the server's constraints. */
export function ApplicationForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
  pending,
  errorMessage,
}: {
  initial?: ApplicationSummary;
  submitLabel: string;
  onSubmit: (input: UpsertApplicationInput) => void;
  onCancel?: () => void;
  pending?: boolean;
  errorMessage?: string;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ApplicationFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      companyName: initial?.companyName ?? "",
      positionTitle: initial?.positionTitle ?? "",
      location: initial?.location ?? "",
      requisitionId: initial?.requisitionId ?? "",
      atsPlatform: initial?.atsPlatform ?? "",
      status: initial?.status ?? "APPLIED",
      appliedAt: toDateInput(initial?.appliedAt),
      notes: "",
    },
  });

  return (
    <form
      className="space-y-4"
      onSubmit={handleSubmit((values) =>
        onSubmit({
          ...values,
          appliedAt: values.appliedAt
            ? new Date(`${values.appliedAt}T12:00:00Z`).toISOString()
            : null,
          notes: values.notes || null,
          location: values.location || null,
          requisitionId: values.requisitionId || null,
          atsPlatform: values.atsPlatform || null,
        }),
      )}
      noValidate
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1">
          <Label htmlFor="companyName">Company</Label>
          <Input id="companyName" {...register("companyName")} aria-invalid={!!errors.companyName} />
          <FieldError message={errors.companyName?.message} />
        </div>

        <div className="space-y-1">
          <Label htmlFor="positionTitle">Position</Label>
          <Input id="positionTitle" {...register("positionTitle")} aria-invalid={!!errors.positionTitle} />
          <FieldError message={errors.positionTitle?.message} />
        </div>

        <div className="space-y-1">
          <Label htmlFor="appliedAt">Date applied</Label>
          <Input id="appliedAt" type="date" {...register("appliedAt")} />
        </div>

        <div className="space-y-1">
          <Label htmlFor="status">Status</Label>
          <Select id="status" {...register("status")}>
            {APPLICATION_STATUSES.map((status) => (
              <option key={status} value={status}>
                {statusLabel(status)}
              </option>
            ))}
          </Select>
        </div>

        <div className="space-y-1">
          <Label htmlFor="location">Location</Label>
          <Input id="location" {...register("location")} placeholder="Columbus, OH" />
        </div>

        <div className="space-y-1">
          <Label htmlFor="requisitionId">Requisition ID</Label>
          <Input id="requisitionId" {...register("requisitionId")} placeholder="JR123456" />
        </div>
      </div>

      <div className="space-y-1">
        <Label htmlFor="notes">Notes</Label>
        <Textarea id="notes" {...register("notes")} placeholder="Referred by…, recruiter said…" />
      </div>

      {errorMessage && (
        <p role="alert" className="text-sm text-danger">
          {errorMessage}
        </p>
      )}

      <div className="flex gap-2">
        <Button type="submit" disabled={pending}>
          {pending ? "Saving…" : submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}
