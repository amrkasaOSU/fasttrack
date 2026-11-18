package com.fasttrack.application;

import com.fasttrack.application.dto.ApplicationRequests.AddNote;
import com.fasttrack.application.dto.ApplicationRequests.ChangeStatus;
import com.fasttrack.application.dto.ApplicationRequests.MergeApplications;
import com.fasttrack.application.dto.ApplicationRequests.ResolveReview;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationDetail;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationPage;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationSummary;
import com.fasttrack.auth.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applications CRUD.
 *
 * <p>No endpoint accepts a user id. The acting user comes from the session via
 * {@link AuthorizationService}, and every service call is scoped to it.
 */
@RestController
@RequestMapping("/api/applications")
@Tag(name = "Applications", description = "Create, read, update, merge and archive applications")
public class ApplicationController {

    private final ApplicationService applications;
    private final AuthorizationService authorization;

    public ApplicationController(ApplicationService applications, AuthorizationService authorization) {
        this.applications = applications;
        this.authorization = authorization;
    }

    @GetMapping
    @Operation(summary = "List applications, newest activity first")
    public ApplicationPage list(
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return applications.list(authorization.requireUserId(), status, search, includeArchived, page, size, sort);
    }

    @GetMapping("/needs-review")
    @Operation(summary = "Applications the system was not confident about")
    public List<ApplicationSummary> needsReview() {
        return applications.needsReview(authorization.requireUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One application with its full status timeline")
    public ApplicationDetail get(@PathVariable UUID id) {
        return applications.detail(authorization.requireUserId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an application by hand")
    public ApplicationSummary create(@Valid @RequestBody UpsertApplication request) {
        return applications.create(authorization.requireUserId(), request,
                ApplicationSource.MANUAL, EventActor.USER);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit an application")
    public ApplicationSummary update(@PathVariable UUID id, @Valid @RequestBody UpsertApplication request) {
        return applications.update(authorization.requireUserId(), id, request);
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Set the status by hand, recording it in the timeline")
    public ApplicationSummary changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatus request) {
        return applications.changeStatus(authorization.requireUserId(), id, request);
    }

    @PostMapping("/{id}/notes")
    @Operation(summary = "Append a note")
    public ApplicationSummary addNote(@PathVariable UUID id, @Valid @RequestBody AddNote request) {
        return applications.addNote(authorization.requireUserId(), id, request);
    }

    @PostMapping("/{id}/merge")
    @Operation(summary = "Merge another application into this one; the other is deleted")
    public ApplicationSummary merge(@PathVariable UUID id, @Valid @RequestBody MergeApplications request) {
        return applications.merge(authorization.requireUserId(), id, request.sourceId());
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive an application (hidden from the default list)")
    public ApplicationSummary archive(@PathVariable UUID id) {
        return applications.setArchived(authorization.requireUserId(), id, true);
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "Restore an archived application")
    public ApplicationSummary unarchive(@PathVariable UUID id) {
        return applications.setArchived(authorization.requireUserId(), id, false);
    }

    @PostMapping("/{id}/resolve-review")
    @Operation(summary = "Clear the needs-review flag")
    public ApplicationSummary resolveReview(@PathVariable UUID id, @RequestBody(required = false) ResolveReview request) {
        return applications.resolveReview(authorization.requireUserId(), id,
                request == null ? null : request.note());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an application and its history")
    public void delete(@PathVariable UUID id) {
        applications.delete(authorization.requireUserId(), id);
    }
}
