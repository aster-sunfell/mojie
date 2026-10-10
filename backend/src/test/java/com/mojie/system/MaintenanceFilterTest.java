package com.mojie.system;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.system.api.MaintenanceFilter;
import com.mojie.system.service.MaintenanceService;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class MaintenanceFilterTest {
  @TempDir Path directory;

  @Test void allWritesAreBlockedButStatusAndReadsRemainAvailable() throws Exception {
    var marker = Files.createFile(directory.resolve("maintenance"));
    var service = new MaintenanceService(marker);
    var filter = new MaintenanceFilter(service);
    for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
      var response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest(method, "/api/v1/skills/import"), response,
          (request, result) -> fail("Maintenance admitted a write"));
      assertEquals(503, response.getStatus());
      assertTrue(response.getContentAsString().contains("MAINTENANCE_MODE"));
      assertEquals("5", response.getHeader("Retry-After"));
    }
    var chain = new MockFilterChain();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/system/maintenance"),
        new MockHttpServletResponse(), chain);
    assertNotNull(chain.getRequest());
    assertEquals(0, service.status().get("activeWrites"));
  }

  @Test void markerCannotCancelAnAdmittedWriteAndItsCounterIsReleasedOnFailure() throws Exception {
    var marker = directory.resolve("maintenance");
    var service = new MaintenanceService(marker);
    var filter = new MaintenanceFilter(service);
    assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(
        new MockHttpServletRequest("PUT", "/api/v1/chapters/c/content"),
        new MockHttpServletResponse(), (request, response) -> {
          assertEquals(1, service.status().get("activeWrites"));
          Files.createFile(marker);
          assertTrue(service.active());
          throw new jakarta.servlet.ServletException("in-flight failure");
        }));
    assertEquals(0, service.status().get("activeWrites"));
    Files.delete(marker);
    assertFalse(service.active());
  }

  @Test void missingConfiguredControlDirectoryFailsClosed() {
    assertTrue(new MaintenanceService(directory.resolve("missing/maintenance")).active());
    assertFalse(new MaintenanceService(null).active());
  }
}
