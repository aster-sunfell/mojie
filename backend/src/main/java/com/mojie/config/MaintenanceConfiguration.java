package com.mojie.config;

import com.mojie.system.service.MaintenanceService;
import java.nio.file.Path;
import org.springframework.context.annotation.*;

@Configuration
public class MaintenanceConfiguration {
  @Bean
  MaintenanceService maintenanceService() {
    String file = System.getenv("MOJIE_MAINTENANCE_FILE");
    return new MaintenanceService(file == null || file.isBlank() ? null : Path.of(file));
  }
}
