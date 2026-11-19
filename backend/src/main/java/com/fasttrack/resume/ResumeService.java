package com.fasttrack.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.common.BadRequestException;
import com.fasttrack.common.NotFoundException;
import com.fasttrack.common.RateLimiter;
import com.fasttrack.config.AppProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resume upload, parsing and versioning.
 *
 * <p>Every upload becomes a new version and the newest becomes active, so a
 * fit check always scores the resume the user last uploaded while older ones
 * stay available for comparison.
 */
@Service
public class ResumeService {

    private final ResumeRepository resumes;
    private final ResumeTextExtractor extractor;
    private final ResumeParser parser;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public ResumeService(ResumeRepository resumes,
                         ResumeTextExtractor extractor,
                         ResumeParser parser,
                         ObjectMapper llmObjectMapper,
                         RateLimiter rateLimiter,
                         AppProperties props) {
        this.resumes = resumes;
        this.extractor = extractor;
        this.parser = parser;
        this.mapper = llmObjectMapper;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public record StoredResume(
            UUID id,
            int version,
            String filename,
            int sizeBytes,
            ResumeParseMethod parseMethod,
            boolean active,
            ResumeProfile profile,
            java.time.Instant createdAt) {}

    @Transactional
    public StoredResume upload(UUID userId, String filename, byte[] content) {
        rateLimiter.checkAndConsume(userId, "upload", props.getRateLimit().getUploadsPerHour());

        String text = extractor.extract(content);
        int version = resumes.highestVersion(userId) + 1;

        Resume resume = new Resume(userId, version, safeFilename(filename), content.length, text);

        ResumeParser.ParsedResume parsed = parser.parse(text, userId);
        resume.storeParsed(toJson(parsed.profile()),
                parsed.method() == ResumeParser.ParseMethod.LLM
                        ? ResumeParseMethod.LLM : ResumeParseMethod.HEURISTIC);

        resumes.findByUserIdAndActiveIsTrue(userId).ifPresent(active -> active.setActive(false));
        resumes.flush();
        resume.setActive(true);

        return toStored(resumes.save(resume));
    }

    /** Saves the user's corrections to the parsed profile. */
    @Transactional
    public StoredResume correct(UUID userId, UUID resumeId, ResumeProfile corrected) {
        Resume resume = require(userId, resumeId);
        resume.markCorrected(toJson(corrected));
        return toStored(resume);
    }

    @Transactional(readOnly = true)
    public List<StoredResume> list(UUID userId) {
        return resumes.findAllByUserIdOrderByVersionDesc(userId).stream().map(this::toStored).toList();
    }

    @Transactional(readOnly = true)
    public StoredResume get(UUID userId, UUID resumeId) {
        return toStored(require(userId, resumeId));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Resume> activeResume(UUID userId) {
        return resumes.findByUserIdAndActiveIsTrue(userId);
    }

    @Transactional
    public StoredResume activate(UUID userId, UUID resumeId) {
        Resume resume = require(userId, resumeId);
        resumes.findByUserIdAndActiveIsTrue(userId).ifPresent(active -> active.setActive(false));
        resumes.flush();
        resume.setActive(true);
        return toStored(resume);
    }

    @Transactional
    public void delete(UUID userId, UUID resumeId) {
        Resume resume = require(userId, resumeId);
        boolean wasActive = resume.isActive();
        resumes.delete(resume);
        resumes.flush();

        if (wasActive) {
            resumes.findAllByUserIdOrderByVersionDesc(userId).stream()
                    .findFirst()
                    .ifPresent(newest -> newest.setActive(true));
        }
    }

    public Resume require(UUID userId, UUID resumeId) {
        return resumes.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new NotFoundException("Resume"));
    }

    public ResumeProfile profileOf(Resume resume) {
        if (resume.getParsedJson() == null || resume.getParsedJson().isBlank()) {
            return ResumeProfile.empty();
        }
        try {
            return mapper.readValue(resume.getParsedJson(), ResumeProfile.class);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return ResumeProfile.empty();
        }
    }

    private StoredResume toStored(Resume resume) {
        return new StoredResume(resume.getId(), resume.getVersion(), resume.getFilename(),
                resume.getSizeBytes(), resume.getParseMethod(), resume.isActive(),
                profileOf(resume), resume.getCreatedAt());
    }

    private String toJson(ResumeProfile profile) {
        try {
            return mapper.writeValueAsString(profile);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new BadRequestException("That resume profile could not be stored.");
        }
    }

    /** Keeps a user-supplied filename from carrying a path or control characters. */
    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "resume.pdf";
        }
        String base = filename.replaceAll(".*[/\\\\]", "").replaceAll("[\\p{Cntrl}]", "").trim();
        return base.isEmpty() ? "resume.pdf" : base.substring(0, Math.min(base.length(), 120));
    }
}
