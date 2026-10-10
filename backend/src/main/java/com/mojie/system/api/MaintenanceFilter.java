package com.mojie.system.api;

import com.mojie.system.service.MaintenanceService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MaintenanceFilter extends OncePerRequestFilter {
  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private final MaintenanceService maintenance;

  public MaintenanceFilter(MaintenanceService maintenance) { this.maintenance = maintenance; }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    boolean write = !READ_METHODS.contains(request.getMethod());
    if (!write) {
      chain.doFilter(request, response);
      return;
    }
    // Count before checking the marker so a drain cannot miss an admitted request.
    maintenance.enteredWrite();
    try {
      if (maintenance.active()) {
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "5");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(
            "{\"code\":\"MAINTENANCE_MODE\",\"message\":\"墨界正在维护，请稍后再试。\"}");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      maintenance.finishedWrite();
    }
  }
}
