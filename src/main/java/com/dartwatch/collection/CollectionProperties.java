package com.dartwatch.collection;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.collection")
public class CollectionProperties {
    private int initialDays = 30;
    private int overlapDays = 3;

    public int getInitialDays() { return initialDays; }
    public void setInitialDays(int value) {
        if (value < 1 || value > 365) throw new IllegalArgumentException("Initial days must be 1..365");
        initialDays = value;
    }
    public int getOverlapDays() { return overlapDays; }
    public void setOverlapDays(int value) {
        if (value < 1 || value > 30) throw new IllegalArgumentException("Overlap days must be 1..30");
        overlapDays = value;
    }
}
