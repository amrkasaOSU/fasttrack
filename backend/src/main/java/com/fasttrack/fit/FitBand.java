package com.fasttrack.fit;

/**
 * How a score reads in words. The boundaries are the ones the product
 * promises: 75+, 55-74, 35-54, below 35.
 */
public enum FitBand {
    STRONG("Strong fit", 75),
    GOOD("Good fit", 55),
    STRETCH("Stretch", 35),
    LONG_SHOT("Long shot", 0);

    private final String label;
    private final int minimumScore;

    FitBand(String label, int minimumScore) {
        this.label = label;
        this.minimumScore = minimumScore;
    }

    public String label() {
        return label;
    }

    public int minimumScore() {
        return minimumScore;
    }

    public static FitBand of(int score) {
        if (score >= STRONG.minimumScore) {
            return STRONG;
        }
        if (score >= GOOD.minimumScore) {
            return GOOD;
        }
        if (score >= STRETCH.minimumScore) {
            return STRETCH;
        }
        return LONG_SHOT;
    }
}
