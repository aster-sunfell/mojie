package com.mojie.proposal.api;

import com.mojie.proposal.model.ProposalDecision;
import com.mojie.proposal.service.ProposalService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ProposalController {
  private final ProposalService proposals;

  public ProposalController(ProposalService proposals) {
    this.proposals = proposals;
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  @GetMapping("/edit-proposals/{id}")
  public Map<String, Object> proposal(@PathVariable String id) {
    return data(proposals.detail(id));
  }

  @PostMapping("/edit-proposals/{id}/accept")
  public ResponseEntity<Map<String, Object>> accept(@PathVariable String id) {
    ProposalDecision result = proposals.accept(id);
    return ResponseEntity.status("CONFLICTED".equals(result.status()) ? 409 : 200)
        .body(data(result));
  }

  @PostMapping("/edit-proposals/{id}/reject")
  public Map<String, Object> reject(@PathVariable String id) {
    return data(proposals.reject(id));
  }
}
