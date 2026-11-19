package com.fasttrack.fit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.classify.EmailContent;
import com.fasttrack.common.BadRequestException;
import com.fasttrack.common.NotFoundException;
import com.fasttrack.common.RateLimiter;
import com.fasttrack.config.AppProperties;
import com.fasttrack.posting.JobPosting;
import com.fasttrack.posting.JobPostingRepository;
import com.fasttrack.posting.PostingParseMethod;
import com.fasttrack.posting.PostingParser;
import com.fasttrack.posting.PostingRequirements;
import com.fasttrack.posting.PostingSource;
import com.fasttrack.resume.Resume;
import com.fasttrack.resume.ResumeProfile;
import com.fasttrack.resume.ResumeService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs a fit check and keeps the result. */
@Service
public class FitService {

    private final ResumeService resumes;
    private final JobPostingRepository postings;
    private final PostingParser postingParser;
    private final ExperienceRater experienceRater;
    private final FitScoreCalculator calculator;
    private final FitCheckRepository fitChecks;
    private final ApplicationRepository applications;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public FitService(ResumeService resumes,
                      JobPostingRepository postings,
                      PostingParser postingParser,
                      ExperienceRater experienceRater,
                      FitScoreCalculator calculator,
                      FitCheckRepository fitChecks,
                      ApplicationRepository applications,
                      ObjectMapper llmObjectMapper,
                      RateLimiter rateLimiter,
                      AppProperties props) {
        this.resumes = resumes;
        this.postings = postings;
        this.postingParser = postingParser;
        this.experienceRater = experienceRater;
        this.calculator = calculator;
        this.fitChecks = fitChecks;
        this.applications = applications;
        this.mapper = llmObjectMapper;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public record FitCheckResult(
            UUID id,
            UUID resumeId,
            UUID jobPostingId,
            UUID applicationId,
            String postingTitle,
            String postingCompany,
            FitScore score,
            PostingRequirements posting,
            java.time.Instant createdAt) {}

    /**
     * Scores the user's active resume against a posting.
     *
     * @param postingText the pasted posting, or the text fetched from a URL
     * @param sourceUrl   the URL it came from, when it was fetched
     */
    @Transactional
    public FitCheckResult check(UUID userId, String postingText, String sourceUrl, UUID applicationId) {
        rateLimiter.checkAndConsume(userId, "fit", props.getRateLimit().getFitChecksPerHour());

        Resume resume = resumes.activeResume(userId)
                .orElseThrow(() -> new BadRequestException(
                        "Upload a resume first - the fit score needs something to compare against."));
        ResumeProfile profile = resumes.profileOf(resume);

        String text = postingText == null ? "" : postingText.trim();
        if (text.length() < 80) {
            throw new BadRequestException(
                    "That posting is too short to score. Paste the full description, including "
                    + "the requirements section.");
        }

        String hash = EmailContent.sha256(text);
        JobPosting posting = postings.findFirstByUserIdAndContentHashOrderByCreatedAtDesc(userId, hash)
                .orElseGet(() -> {
                    JobPosting fresh = new JobPosting(userId, text, hash,
                            sourceUrl == null ? PostingSource.PASTED : PostingSource.URL);
                    fresh.setSourceUrl(sourceUrl);
                    return postings.save(fresh);
                });

        PostingRequirements requirements;
        if (posting.getParsedJson() != null && !posting.getParsedJson().isBlank()) {
            requirements = readRequirements(posting.getParsedJson());
        } else {
            PostingParser.ParsedPosting parsed = postingParser.parse(text, userId);
            requirements = parsed.requirements();
            posting.storeParsed(writeJson(requirements),
                    parsed.method() == PostingParser.ParseMethod.LLM
                            ? PostingParseMethod.LLM : PostingParseMethod.HEURISTIC,
                    requirements.title(), requirements.company());
        }

        FitScoreCalculator.ExperienceRating rating =
                experienceRater.rate(profile, requirements, userId);

        FitScore score = calculator.score(profile, requirements, rating, resume.getContentText());

        FitCheck stored = new FitCheck(userId, resume.getId(), posting.getId(), score, writeJson(score));
        if (applicationId != null) {
            applications.findByIdAndUserId(applicationId, userId)
                    .orElseThrow(() -> new NotFoundException("Application"));
            stored.linkTo(applicationId);
        }
        fitChecks.save(stored);

        return new FitCheckResult(stored.getId(), resume.getId(), posting.getId(), applicationId,
                posting.getTitle(), posting.getCompany(), score, requirements, stored.getCreatedAt());
    }

    /** Fetches a posting URL, then scores it. */
    @Transactional
    public FitCheckResult checkUrl(UUID userId, String url, UUID applicationId) {
        return check(userId, postingParser.fetch(url), url, applicationId);
    }

    @Transactional(readOnly = true)
    public List<FitCheckResult> recent(UUID userId) {
        return fitChecks.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResult)
                .toList();
    }

    @Transactional(readOnly = true)
    public FitCheckResult get(UUID userId, UUID fitCheckId) {
        return toResult(fitChecks.findByIdAndUserId(fitCheckId, userId)
                .orElseThrow(() -> new NotFoundException("Fit check")));
    }

    @Transactional
    public FitCheckResult linkToApplication(UUID userId, UUID fitCheckId, UUID applicationId) {
        FitCheck check = fitChecks.findByIdAndUserId(fitCheckId, userId)
                .orElseThrow(() -> new NotFoundException("Fit check"));
        applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> new NotFoundException("Application"));
        check.linkTo(applicationId);
        return toResult(check);
    }

    @Transactional(readOnly = true)
    public List<FitCheckResult> forApplication(UUID userId, UUID applicationId) {
        return fitChecks.findAllByUserIdAndApplicationIdOrderByCreatedAtDesc(userId, applicationId)
                .stream().map(this::toResult).toList();
    }

    private FitCheckResult toResult(FitCheck check) {
        JobPosting posting = postings.findById(check.getJobPostingId()).orElse(null);
        FitScore score = readScore(check.getDetailsJson());
        PostingRequirements requirements = posting == null || posting.getParsedJson() == null
                ? PostingRequirements.empty()
                : readRequirements(posting.getParsedJson());

        return new FitCheckResult(check.getId(), check.getResumeId(), check.getJobPostingId(),
                check.getApplicationId(),
                posting == null ? null : posting.getTitle(),
                posting == null ? null : posting.getCompany(),
                score, requirements, check.getCreatedAt());
    }

    private PostingRequirements readRequirements(String json) {
        try {
            return mapper.readValue(json, PostingRequirements.class);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return PostingRequirements.empty();
        }
    }

    private FitScore readScore(String json) {
        try {
            return mapper.readValue(json, FitScore.class);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("A stored fit check could not be read back", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Could not store the fit check", ex);
        }
    }
}
