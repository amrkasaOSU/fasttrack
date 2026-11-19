package com.fasttrack.resume;

import com.fasttrack.auth.AuthorizationService;
import com.fasttrack.common.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

/** Resume upload, correction and versions. */
@RestController
@RequestMapping("/api/resumes")
@Tag(name = "Resume", description = "Upload a resume, correct what was parsed, manage versions")
public class ResumeController {

    private final ResumeService resumes;
    private final AuthorizationService authorization;

    public ResumeController(ResumeService resumes, AuthorizationService authorization) {
        this.resumes = resumes;
        this.authorization = authorization;
    }

    @GetMapping
    @Operation(summary = "Your resume versions, newest first")
    public List<ResumeService.StoredResume> list() {
        return resumes.list(authorization.requireUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One resume version with its parsed profile")
    public ResumeService.StoredResume get(@PathVariable UUID id) {
        return resumes.get(authorization.requireUserId(), id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a PDF resume (max 2 MB); the parsed result is returned for review")
    public ResumeService.StoredResume upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a PDF to upload.");
        }
        if (file.getSize() > ResumeTextExtractor.MAX_BYTES) {
            throw new BadRequestException("Resumes are limited to 2 MB.");
        }
        try {
            return resumes.upload(authorization.requireUserId(),
                    file.getOriginalFilename(), file.getBytes());
        } catch (IOException ex) {
            throw new BadRequestException("The upload could not be read.");
        }
    }

    @PutMapping("/{id}/profile")
    @Operation(summary = "Save your corrections to the parsed profile")
    public ResumeService.StoredResume correct(@PathVariable UUID id,
                                              @Valid @RequestBody ResumeProfile profile) {
        return resumes.correct(authorization.requireUserId(), id, profile);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Use this version for fit checks")
    public ResumeService.StoredResume activate(@PathVariable UUID id) {
        return resumes.activate(authorization.requireUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a resume version")
    public void delete(@PathVariable UUID id) {
        resumes.delete(authorization.requireUserId(), id);
    }
}
