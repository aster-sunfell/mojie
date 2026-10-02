package com.mojie.agent.model;

public record RunEvent(long seq, String type, Object payload, String occurredAt) {}
