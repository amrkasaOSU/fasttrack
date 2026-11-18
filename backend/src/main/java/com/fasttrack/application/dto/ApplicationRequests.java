package com.fasttrack.application.dto;

import com.fasttrack.application.ApplicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request bodies for the applications API. */
public final class ApplicationRequests {

    private ApplicationRequests() {
    }

    /** Create or fully update an application. No user id: it comes from the session. */
    public record UpsertApplication(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String positionTitle,
            @Size(max = 100) String requisitionId,
            @Size(max = 200) String location,
            @Size(max = 80) String atsPlatform,
            ApplicationStatus status,
            Instant appliedAt,
            @Size(max = 4000) String notes) {}

    /** Change only the status, recording why. */
    public record ChangeStatus(
            @NotNull ApplicationStatus status,
            @Size(max = 500) String note) {}

    /** Fold one application into another; the source is deleted. */
    public record MergeApplications(
            @NotNull UUID sourceId) {}

    public record AddNote(
            @NotBlank @Size(max = 2000) String note) {}

    public record ResolveReview(
            @Size(max = 500) String note) {}
}
