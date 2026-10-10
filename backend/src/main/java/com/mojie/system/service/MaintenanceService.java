package com.mojie.system.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A host-owned marker, deliberately independent of the database being migrated. */
public final class MaintenanceService {
  private final Path marker;
  private final AtomicInteger writes = new AtomicInteger();

  public MaintenanceService(Path marker) {
    this.marker = marker;
  }

  public boolean active() {
    if (marker == null) return false;
    try {
      Files.readAttributes(marker, BasicFileAttributes.class);
      return true;
    } catch (NoSuchFileException absent) {
      return !Files.isDirectory(marker.getParent());
    } catch (IOException | SecurityException unreadable) {
      return true;
    }
  }

  public void enteredWrite() { writes.incrementAndGet(); }
  public void finishedWrite() { writes.decrementAndGet(); }

  public Map<String, Object> status() {
    return Map.of("active", active(), "activeWrites", writes.get(), "retryAfterSeconds", 5);
  }
}
