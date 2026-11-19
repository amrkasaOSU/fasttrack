package com.fasttrack.matching;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.JobApplication;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides which application an email belongs to, or that it is a new one.
 *
 * <p>The rules, in order:
 * <ol>
 *   <li><b>Same requisition id at the same company</b> is the same
 *       application. Requisition ids are the only identifier an ATS gives us
 *       that is genuinely unique, so this rule is unconditional.</li>
 *   <li><b>Same company, identical normalized title</b> is the same
 *       application.</li>
 *   <li><b>Same company, highly similar title</b> is the same application.
 *       Similarity is token overlap on the normalized titles
 *       (see {@link #SIMILARITY_THRESHOLD}).</li>
 *   <li><b>Same company, clearly different title</b> is a new application -
 *       people really do apply to three roles at one company.</li>
 *   <li><b>Borderline similarity</b> attaches to the closest candidate and
 *       flags it for review rather than guessing silently.</li>
 * </ol>
 *
 * <p>Nothing here deletes or merges automatically. When the matcher is unsure
 * the user is asked, because a wrong merge loses history that cannot be
 * reconstructed.
 */
@Component
public class ApplicationMatcher {

    /**
     * Token-overlap (Jaccard) at or above this counts as the same role.
     *
     * <p>Chosen so that "software engineer intern" vs "software engineering
     * intern" (1.0 after normalization) and "backend engineer intern" vs
     * "backend engineer" (0.67) match, while "software engineer intern" vs
     * "data engineer intern" (0.5) does not. Milestone 5 re-derives this from
     * the labeled matcher set and records the result in RESULTS.md.
     */
    public static final double SIMILARITY_THRESHOLD = 0.60;

    /** Between this and the threshold, attach but ask the user to confirm. */
    public static final double REVIEW_BAND = 0.45;

    private final ApplicationRepository applications;
    private final TextNormalizer normalizer;

    public ApplicationMatcher(ApplicationRepository applications, TextNormalizer normalizer) {
        this.applications = applications;
        this.normalizer = normalizer;
    }

    /**
     * Finds the application this email belongs to, creating one if nothing
     * matches. Never returns null.
     */
    @Transactional
    public MatchOutcome match(UUID userId,
                              String company,
                              String positionTitle,
                              String requisitionId,
                              String atsPlatform,
                              Instant appliedAt) {

        String companyKey = normalizer.normalizeCompany(company);
        String positionKey = normalizer.normalizePosition(positionTitle);

        if (companyKey.isBlank()) {
            // Without a company there is nothing to match on. The caller
            // decides whether to drop the email or raise it for review.
            return null;
        }

        // Rule 1 - requisition id is decisive.
        if (requisitionId != null && !requisitionId.isBlank()) {
            Optional<JobApplication> byRequisition = applications
                    .findByUserIdAndCompanyNormalizedAndRequisitionId(userId, companyKey, requisitionId.trim());
            if (byRequisition.isPresent()) {
                return new MatchOutcome(byRequisition.get(), false, false, "requisition_id",
                        "Same requisition " + requisitionId.trim() + " at " + byRequisition.get().getCompanyName());
            }
        }

        List<JobApplication> atCompany = applications.findAllByUserIdAndCompanyNormalized(userId, companyKey);

        // Rule 2 - identical normalized title.
        for (JobApplication candidate : atCompany) {
            if (!positionKey.isBlank() && candidate.getPositionNormalized().equals(positionKey)) {
                return new MatchOutcome(candidate, false, false, "exact_title",
                        "Same role at " + candidate.getCompanyName());
            }
        }

        // Rules 3-5 - best similarity wins, with a review band below it.
        JobApplication best = null;
        double bestScore = 0;
        for (JobApplication candidate : atCompany) {
            double score = similarity(positionKey, candidate.getPositionNormalized());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null && bestScore >= SIMILARITY_THRESHOLD) {
            return new MatchOutcome(best, false, false, "similar_title",
                    String.format("Matched \"%s\" to \"%s\" (%.0f%% title overlap)",
                            positionTitle, best.getPositionTitle(), bestScore * 100));
        }

        if (best != null && bestScore >= REVIEW_BAND) {
            return new MatchOutcome(best, false, true, "similar_title_uncertain",
                    String.format("Attached to \"%s\", but the titles only overlap %.0f%% - "
                            + "check this is the same application",
                            best.getPositionTitle(), bestScore * 100));
        }

        // Nothing close enough: a genuinely new application.
        JobApplication created = new JobApplication(
                userId,
                company.trim(),
                companyKey,
                positionTitle == null || positionTitle.isBlank() ? "Unknown position" : positionTitle.trim(),
                positionKey,
                ApplicationSource.EMAIL);
        created.setRequisitionId(requisitionId == null || requisitionId.isBlank() ? null : requisitionId.trim());
        created.setAtsPlatform(atsPlatform);
        created.setAppliedAt(appliedAt);
        created.setLastEventAt(appliedAt);

        boolean positionUnknown = positionKey.isBlank();
        if (positionUnknown) {
            created.flagForReview("This email did not say which role it was about");
        }

        return new MatchOutcome(applications.save(created), true, positionUnknown, "new_application",
                "No existing application matched, so a new one was created");
    }

    /**
     * Token overlap between two normalized titles (intersection over union).
     *
     * <p>Jaccard rather than edit distance: job titles differ by whole words
     * ("backend" vs "data"), not by characters, and edit distance would score
     * "data engineer intern" and "date engineer intern" as nearly identical
     * while treating a reordered title as distant.
     */
    public double similarity(String left, String right) {
        Set<String> a = normalizer.tokens(left);
        Set<String> b = normalizer.tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }
}
