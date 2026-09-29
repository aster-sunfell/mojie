package com.mojie.agent.loop;

public record LoopLimits(int maxModelCalls, int maxToolCalls, int maxToolTextCodePoints,
                         int maxContextCodePoints, int maxContextTokens, long maxRequestMillis) {
    public LoopLimits(int maxModelCalls, int maxToolCalls, int maxToolTextCodePoints,
                      int maxContextCodePoints, long maxRequestMillis) {
        this(maxModelCalls, maxToolCalls, maxToolTextCodePoints, maxContextCodePoints, 1_000_000, maxRequestMillis);
    }
    public static LoopLimits defaults() { return new LoopLimits(8, 12, 30_000, 200_000, 1_000_000, 60_000); }
    public LoopLimits withContextWindowTokens(int window) {
        if (window <= 0) throw new IllegalArgumentException("Context window must be positive");
        return new LoopLimits(maxModelCalls, maxToolCalls, maxToolTextCodePoints,
                maxContextCodePoints, window, maxRequestMillis);
    }
}
