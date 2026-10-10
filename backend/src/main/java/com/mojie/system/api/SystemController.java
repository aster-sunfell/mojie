package com.mojie.system.api;

import com.mojie.system.model.HealthStatus;
import com.mojie.system.service.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class SystemController {
  private final HealthService health;
  private final CapabilityService capabilities;
  private final MaintenanceService maintenance;

  public SystemController(HealthService health, CapabilityService capabilities, MaintenanceService maintenance) {
    this.health = health;
    this.capabilities = capabilities;
    this.maintenance = maintenance;
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    HealthStatus result = health.health();
    return ResponseEntity.status("DOWN".equals(result.status()) ? 503 : 200)
        .body(Map.of("data", result));
  }

  @GetMapping("/system/capabilities")
  public Map<String, Object> capabilities() {
    return Map.of("data", capabilities.capabilities());
  }

  @GetMapping("/system/maintenance")
  public ResponseEntity<Map<String, Object>> maintenance() {
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(Map.of("data", maintenance.status()));
  }
}
