package com.fasttrack.insight;

import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.JobApplication;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.fit.FitBand;
import com.fasttrack.fit.FitCheck;
import com.fasttrack.fit.FitCheckRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the dashboard shows, computed from the user's own data.
 *
 * <p>Two rules run through this class:
 * <ul>
 *   <li><b>Never invent a denominator.</b> A rate over three applications is
 *       noise, so every rate carries the counts it was computed from and the
 *       UI shows them.</li>
 *   <li><b>Silence is not rejection.</b> "No response" is its own outcome
 *       everywhere here; it is never folded into rejections to make a funnel
 *       look tidier.</li>
 * </ul>
 */
@Service
public class InsightsService {

    /** After this long with no reply, an application is worth chasing. */
    public static final int FOLLOW_UP_DAYS = 21;

    /** Below this many scored applications, fit-vs-outcome is not shown. */
    public static final int MIN_FIT_SAMPLES = 8;

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final EmailMessageRepository emails;
    private final EmailClassificationRepository classifications;
    private final FitCheckRepository fitChecks;

    public InsightsService(ApplicationRepository applications,
                           ApplicationEventRepository events,
                           EmailMessageRepository emails,
                           EmailClassificationRepository classifications,
                           FitCheckRepository fitChecks) {
        this.applications = applications;
        this.events = events;
        this.emails = emails;
        this.classifications = classifications;
        this.fitChecks = fitChecks;
    }

    // ------------------------------------------------------------- records

    public record Totals(
            long applications,
            long companies,
            long responses,
            long noResponse,
            long rejections,
            long assessments,
            long interviews,
            long offers,
            long active,
            long archived,
            Double responseRate,
            Double interviewRate,
            Double offerRate) {}

    public record WeeklyPoint(String week, LocalDate weekStart, long applied) {}

    public record FunnelStage(String stage, long count, Double shareOfApplied) {}

    public record GroupedRate(String group, long applications, long responses, Double responseRate) {}

    public record TimingStats(
            Double averageDaysToFirstResponse,
            Double medianDaysToFirstResponse,
            Double averageDaysToRejection,
            long measuredFrom) {}

    public record ActionItem(
            String kind,
            UUID applicationId,
            String company,
            String position,
            String detail,
            Instant dueAt,
            Long daysUntil) {}

    public record FitBandOutcome(String band, long applications, long responses, Double responseRate) {}

    public record Insights(
            Totals totals,
            List<WeeklyPoint> applicationsPerWeek,
            List<FunnelStage> funnel,
            List<GroupedRate> byPlatform,
            List<GroupedRate> byLocationType,
            TimingStats timing,
            List<ActionItem> actionItems,
            long needsReviewCount,
            List<FitBandOutcome> fitVsOutcome,
            String fitVsOutcomeNote) {}

    // --------------------------------------------------------------- entry

    @Transactional(readOnly = true)
    public Insights forUser(UUID userId) {
        List<JobApplication> all = applications.findAllByUserIdOrderByCreatedAtDesc(userId);
        List<JobApplication> live = all.stream().filter(a -> !a.isArchived()).toList();
        Map<UUID, Integer> furthestRank = furthestStageReached(userId);

        return new Insights(
                totals(live, furthestRank, all.size() - live.size()),
                applicationsPerWeek(live),
                funnel(live, furthestRank),
                groupBy(live, this::platformOf),
                groupBy(live, this::locationTypeOf),
                timing(live),
                actionItems(userId, live),
                applications.countByUserIdAndNeedsReviewIsTrue(userId)
                        + classifications.countByUserIdAndNeedsReviewIsTrue(userId),
                fitVsOutcome(userId, live),
                fitNote(userId, live));
    }

    // -------------------------------------------------------------- totals

    private Totals totals(List<JobApplication> live, Map<UUID, Integer> furthestRank, int archived) {
        long total = live.size();
        long companies = live.stream().map(JobApplication::getCompanyNormalized).distinct().count();
        long responses = live.stream().filter(a -> a.getFirstResponseAt() != null).count();
        long rejections = count(live, ApplicationStatus.REJECTED);
        long assessments = countReached(live, furthestRank, ApplicationStatus.ASSESSMENT_REQUESTED);
        long interviews = countReached(live, furthestRank, ApplicationStatus.INTERVIEW_REQUESTED);
        long offers = countReached(live, furthestRank, ApplicationStatus.OFFER_RECEIVED);
        long active = live.stream().filter(a -> a.getStatus().isActive()).count();

        return new Totals(total, companies, responses, total - responses, rejections,
                assessments, interviews, offers, active, archived,
                rate(responses, total), rate(interviews, total), rate(offers, total));
    }

    /**
     * The furthest stage each application ever reached, from its append-only
     * history.
     *
     * <p>Current status is not enough: an application rejected after an
     * on-site shows {@code REJECTED}, and counting that as "never
     * interviewed" would make the funnel lie in the most flattering-to-nobody
     * direction. One query over the user's status-change events answers it
     * for every application at once.
     */
    private Map<UUID, Integer> furthestStageReached(UUID userId) {
        Map<UUID, Integer> furthest = new HashMap<>();
        for (ApplicationEvent event : events.findAllByUserIdAndEventType(
                userId, ApplicationEventType.STATUS_CHANGED)) {
            if (event.getToStatus() == null) {
                continue;
            }
            int rank = event.getToStatus().rank();
            if (rank < 0) {
                continue; // terminal states carry no funnel position
            }
            furthest.merge(event.getApplicationId(), rank, Math::max);
        }
        return furthest;
    }

    /** How far an application got: its history, or its current status if newer. */
    private int rankOf(JobApplication application, Map<UUID, Integer> furthestRank) {
        int fromHistory = furthestRank.getOrDefault(application.getId(), -1);
        int fromStatus = application.getStatus().rank();
        return Math.max(fromHistory, fromStatus);
    }

    private long countReached(List<JobApplication> applications, Map<UUID, Integer> furthestRank,
                              ApplicationStatus stage) {
        return applications.stream()
                .filter(application -> rankOf(application, furthestRank) >= stage.rank())
                .count();
    }

    private long count(List<JobApplication> applications, ApplicationStatus status) {
        return applications.stream().filter(a -> a.getStatus() == status).count();
    }

    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    // ------------------------------------------------------------- weekly

    private List<WeeklyPoint> applicationsPerWeek(List<JobApplication> applications) {
        Map<LocalDate, Long> byWeek = new LinkedHashMap<>();
        WeekFields weekFields = WeekFields.of(Locale.US);

        for (JobApplication application : applications) {
            Instant appliedAt = application.getAppliedAt() == null
                    ? application.getCreatedAt() : application.getAppliedAt();
            LocalDate date = appliedAt.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate weekStart = date.with(weekFields.dayOfWeek(), 1);
            byWeek.merge(weekStart, 1L, Long::sum);
        }

        return byWeek.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new WeeklyPoint(
                        entry.getKey().toString(), entry.getKey(), entry.getValue()))
                .toList();
    }

    // -------------------------------------------------------------- funnel

    /**
     * Applied -> responded -> assessment -> interview -> offer.
     *
     * <p>Each stage counts applications that reached it <em>or beyond</em>,
     * including ones later rejected: a rejection after an on-site is still an
     * on-site. That needs the history, so a rejected application is credited
     * with the furthest stage its timeline shows.
     */
    private List<FunnelStage> funnel(List<JobApplication> applications, Map<UUID, Integer> furthestRank) {
        long applied = applications.size();
        long responded = applications.stream().filter(a -> a.getFirstResponseAt() != null).count();
        long assessment = countReached(applications, furthestRank, ApplicationStatus.ASSESSMENT_REQUESTED);
        long interview = countReached(applications, furthestRank, ApplicationStatus.INTERVIEW_REQUESTED);
        long offer = countReached(applications, furthestRank, ApplicationStatus.OFFER_RECEIVED);

        List<FunnelStage> stages = new ArrayList<>();
        stages.add(new FunnelStage("Applied", applied, applied == 0 ? null : 1.0));
        stages.add(new FunnelStage("Responded", responded, rate(responded, applied)));
        stages.add(new FunnelStage("Assessment", assessment, rate(assessment, applied)));
        stages.add(new FunnelStage("Interview", interview, rate(interview, applied)));
        stages.add(new FunnelStage("Offer", offer, rate(offer, applied)));
        return stages;
    }

    // ------------------------------------------------------------ grouping

    private List<GroupedRate> groupBy(List<JobApplication> applications,
                                      java.util.function.Function<JobApplication, String> key) {
        Map<String, long[]> counts = new HashMap<>();
        for (JobApplication application : applications) {
            long[] pair = counts.computeIfAbsent(key.apply(application), k -> new long[2]);
            pair[0]++;
            if (application.getFirstResponseAt() != null) {
                pair[1]++;
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new GroupedRate(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                        rate(entry.getValue()[1], entry.getValue()[0])))
                .sorted(Comparator.comparingLong(GroupedRate::applications).reversed())
                .toList();
    }

    private String platformOf(JobApplication application) {
        String platform = application.getAtsPlatform();
        return platform == null || platform.isBlank() ? "Unknown" : platform;
    }

    private String locationTypeOf(JobApplication application) {
        String location = application.getLocation();
        if (location == null || location.isBlank()) {
            return "Unknown";
        }
        String lowered = location.toLowerCase(Locale.ROOT);
        if (lowered.contains("remote")) {
            return "Remote";
        }
        if (lowered.contains("hybrid")) {
            return "Hybrid";
        }
        return "On site";
    }

    // -------------------------------------------------------------- timing

    private TimingStats timing(List<JobApplication> applications) {
        List<Double> toResponse = new ArrayList<>();
        List<Double> toRejection = new ArrayList<>();

        for (JobApplication application : applications) {
            if (application.getAppliedAt() == null) {
                continue;
            }
            if (application.getFirstResponseAt() != null) {
                double days = days(application.getAppliedAt(), application.getFirstResponseAt());
                if (days >= 0) {
                    toResponse.add(days);
                }
            }
            if (application.getStatus() == ApplicationStatus.REJECTED
                    && application.getLastEventAt() != null) {
                double days = days(application.getAppliedAt(), application.getLastEventAt());
                if (days >= 0) {
                    toRejection.add(days);
                }
            }
        }

        return new TimingStats(average(toResponse), median(toResponse), average(toRejection),
                toResponse.size());
    }

    private double days(Instant from, Instant to) {
        return Duration.between(from, to).toHours() / 24.0;
    }

    private Double average(List<Double> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    private Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        double median = sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        return Math.round(median * 10) / 10.0;
    }

    // --------------------------------------------------------- action items

    private List<ActionItem> actionItems(UUID userId, List<JobApplication> live) {
        List<ActionItem> items = new ArrayList<>();
        Instant now = Instant.now();

        // Deadlines and interview times come from the classified emails that
        // are attached to each application.
        for (JobApplication application : live) {
            emails.findAllByApplicationIdAndUserIdOrderByReceivedAtDesc(application.getId(), userId)
                    .forEach(email -> classifications.findByEmailId(email.getId()).ifPresent(classification -> {
                        if (classification.getAssessmentDeadline() != null
                                && classification.getAssessmentDeadline().isAfter(now.minus(1, ChronoUnit.DAYS))) {
                            items.add(new ActionItem("ASSESSMENT_DEADLINE", application.getId(),
                                    application.getCompanyName(), application.getPositionTitle(),
                                    "Assessment due" + platformSuffix(classification),
                                    classification.getAssessmentDeadline(),
                                    daysBetween(now, classification.getAssessmentDeadline())));
                        }
                        if (classification.getInterviewDatetime() != null
                                && classification.getInterviewDatetime().isAfter(now.minus(1, ChronoUnit.DAYS))) {
                            items.add(new ActionItem("INTERVIEW", application.getId(),
                                    application.getCompanyName(), application.getPositionTitle(),
                                    "Interview scheduled", classification.getInterviewDatetime(),
                                    daysBetween(now, classification.getInterviewDatetime())));
                        }
                    }));
        }

        // Assessments requested with no deadline found still need chasing.
        for (JobApplication application : live) {
            if (application.getStatus() == ApplicationStatus.ASSESSMENT_REQUESTED
                    && items.stream().noneMatch(item -> item.applicationId().equals(application.getId()))) {
                items.add(new ActionItem("ASSESSMENT_PENDING", application.getId(),
                        application.getCompanyName(), application.getPositionTitle(),
                        "Assessment requested, no deadline found in the email", null, null));
            }
        }

        // Applications with no reply at all after three weeks.
        for (JobApplication application : applications.findSilentSince(userId,
                Instant.now().minus(FOLLOW_UP_DAYS, ChronoUnit.DAYS))) {
            long days = (long) days(application.getAppliedAt(), now);
            items.add(new ActionItem("NO_RESPONSE", application.getId(),
                    application.getCompanyName(), application.getPositionTitle(),
                    "No reply after " + days + " days", null, -days));
        }

        items.sort(Comparator.comparing(
                (ActionItem item) -> item.daysUntil() == null ? Long.MAX_VALUE : item.daysUntil()));
        return items;
    }

    private String platformSuffix(EmailClassification classification) {
        return classification.getPlatform() == null ? "" : " on " + classification.getPlatform();
    }

    private Long daysBetween(Instant from, Instant to) {
        return Duration.between(from, to).toDays();
    }

    // -------------------------------------------------------- fit outcomes

    /**
     * Response rate by fit band - the only claim this product can make about
     * whether its own score predicts anything. It is withheld until there are
     * enough scored applications for the comparison to mean something.
     */
    private List<FitBandOutcome> fitVsOutcome(UUID userId, List<JobApplication> live) {
        List<FitCheck> linked = fitChecks.findAllByUserIdAndApplicationIdIsNotNull(userId);
        if (linked.size() < MIN_FIT_SAMPLES) {
            return List.of();
        }

        Map<UUID, JobApplication> byId = new HashMap<>();
        live.forEach(application -> byId.put(application.getId(), application));

        Map<FitBand, long[]> counts = new EnumMap<>(FitBand.class);
        for (FitCheck check : linked) {
            JobApplication application = byId.get(check.getApplicationId());
            if (application == null) {
                continue;
            }
            long[] pair = counts.computeIfAbsent(check.getBand(), band -> new long[2]);
            pair[0]++;
            if (application.getFirstResponseAt() != null) {
                pair[1]++;
            }
        }

        List<FitBandOutcome> outcomes = new ArrayList<>();
        for (FitBand band : FitBand.values()) {
            long[] pair = counts.get(band);
            if (pair != null && pair[0] > 0) {
                outcomes.add(new FitBandOutcome(band.name(), pair[0], pair[1], rate(pair[1], pair[0])));
            }
        }
        return outcomes;
    }

    private String fitNote(UUID userId, List<JobApplication> live) {
        long linked = fitChecks.findAllByUserIdAndApplicationIdIsNotNull(userId).size();
        if (linked >= MIN_FIT_SAMPLES) {
            return "Response rate by fit band, over " + linked + " scored applications. "
                 + "Treat it as a hint, not a law - the sample is your own applications.";
        }
        return "Link at least " + MIN_FIT_SAMPLES + " fit checks to applications and this will "
             + "show whether your score predicts a reply. You have " + linked + ".";
    }

    /** Used by the dashboard's empty state. */
    @Transactional(readOnly = true)
    public boolean hasAnyData(UUID userId) {
        return applications.countByUserId(userId) > 0;
    }

}
