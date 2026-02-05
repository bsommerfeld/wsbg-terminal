package de.bsommerfeld.wsbg.terminal.core.domain;

/**
 * Represents a single training record for the ML model.
 */
public class TrainingData {

    private final String features; // JSON or CSV string of features
    private final int label; // 0=BUY, 1=SELL, 2=HOLD

    public TrainingData(String features, int label) {
        this.features = features;
        this.label = label;
    }

    public String getFeatures() {
        return features;
    }

    public int getLabel() {
        return label;
    }
}
