package com.fasttrack.eval;

import com.fasttrack.classify.EmailCategory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Confusion matrix and per-category precision / recall / F1 for a set of
 * predictions.
 *
 * <p>Macro-F1 is reported alongside overall accuracy on purpose: the
 * evaluation set is imbalanced (45 rejections, 7 cancellations), so accuracy
 * alone would hide a classifier that ignores the rare categories.
 */
public final class ClassificationMetrics {

    private final Map<EmailCategory, Map<EmailCategory, Integer>> matrix = new EnumMap<>(EmailCategory.class);
    private final List<Long> latencies = new ArrayList<>();
    private int total;
    private int correct;

    public ClassificationMetrics() {
        for (EmailCategory actual : EmailCategory.values()) {
            Map<EmailCategory, Integer> row = new EnumMap<>(EmailCategory.class);
            for (EmailCategory predicted : EmailCategory.values()) {
                row.put(predicted, 0);
            }
            matrix.put(actual, row);
        }
    }

    public void record(EmailCategory expected, EmailCategory predicted, long latencyMs) {
        matrix.get(expected).merge(predicted, 1, Integer::sum);
        latencies.add(latencyMs);
        total++;
        if (expected == predicted) {
            correct++;
        }
    }

    public int total() {
        return total;
    }

    public double accuracy() {
        return total == 0 ? 0 : (double) correct / total;
    }

    public int truePositives(EmailCategory category) {
        return matrix.get(category).get(category);
    }

    public int predictedCount(EmailCategory category) {
        int sum = 0;
        for (EmailCategory actual : EmailCategory.values()) {
            sum += matrix.get(actual).get(category);
        }
        return sum;
    }

    public int actualCount(EmailCategory category) {
        int sum = 0;
        for (int value : matrix.get(category).values()) {
            sum += value;
        }
        return sum;
    }

    public double precision(EmailCategory category) {
        int predicted = predictedCount(category);
        return predicted == 0 ? 0 : (double) truePositives(category) / predicted;
    }

    public double recall(EmailCategory category) {
        int actual = actualCount(category);
        return actual == 0 ? 0 : (double) truePositives(category) / actual;
    }

    public double f1(EmailCategory category) {
        double p = precision(category);
        double r = recall(category);
        return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }

    /** Unweighted mean F1 over the categories that actually appear in the set. */
    public double macroF1() {
        double sum = 0;
        int counted = 0;
        for (EmailCategory category : EmailCategory.values()) {
            if (actualCount(category) > 0) {
                sum += f1(category);
                counted++;
            }
        }
        return counted == 0 ? 0 : sum / counted;
    }

    public long averageLatencyMs() {
        return latencies.isEmpty() ? 0
                : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    public long percentileLatencyMs(double percentile) {
        if (latencies.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    /** The per-category table, as markdown. */
    public String perCategoryTable() {
        StringBuilder out = new StringBuilder(
                "| Category | Support | Precision | Recall | F1 |\n|---|---:|---:|---:|---:|\n");
        for (EmailCategory category : EmailCategory.values()) {
            int support = actualCount(category);
            if (support == 0) {
                continue;
            }
            out.append(String.format("| %s | %d | %.3f | %.3f | %.3f |%n",
                    category, support, precision(category), recall(category), f1(category)));
        }
        return out.toString();
    }

    /** The confusion matrix, as markdown. Rows are truth, columns predictions. */
    public String confusionMatrix() {
        List<EmailCategory> present = new ArrayList<>();
        for (EmailCategory category : EmailCategory.values()) {
            if (actualCount(category) > 0 || predictedCount(category) > 0) {
                present.add(category);
            }
        }

        StringBuilder out = new StringBuilder("| actual \\ predicted |");
        for (EmailCategory category : present) {
            out.append(' ').append(shortName(category)).append(" |");
        }
        out.append("\n|---|");
        out.append("---:|".repeat(present.size()));
        out.append('\n');

        for (EmailCategory actual : present) {
            out.append("| **").append(shortName(actual)).append("** |");
            for (EmailCategory predicted : present) {
                int count = matrix.get(actual).get(predicted);
                out.append(' ').append(count == 0 ? "." : String.valueOf(count)).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Abbreviations keep the matrix readable in a terminal and on GitHub. */
    static String shortName(EmailCategory category) {
        return switch (category) {
            case APPLICATION_CONFIRMATION -> "CONF";
            case REJECTION -> "REJ";
            case ASSESSMENT_INVITE -> "ASMT";
            case INTERVIEW_REQUEST -> "IVREQ";
            case INTERVIEW_SCHEDULED -> "IVSCH";
            case OFFER -> "OFFER";
            case RECRUITER_OUTREACH -> "OUTR";
            case POSITION_CANCELLED -> "CANC";
            case SURVEY_OR_ACCOUNT -> "SURV";
            case NOT_JOB_RELATED -> "NJR";
        };
    }
}
