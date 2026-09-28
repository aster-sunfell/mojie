package com.mojie.agent.loop;

public record LoopLimits(int maxModelCalls, int maxToolCalls, int maxToolTextCodePoints,
                         int maxContextCodePoints, long maxRequestMillis) {
    public static LoopLimits defaults() { return new LoopLimits(8, 12, 30_000, 200_000, 60_000); }
}
