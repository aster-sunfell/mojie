package com.mojie.proposal.api;

import com.mojie.proposal.service.ProposalInboxService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{id}/edit-proposals")
public final class ProposalInboxController {
  private final ProposalInboxService inbox;

  public ProposalInboxController(ProposalInboxService inbox) {
    this.inbox = inbox;
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(@PathVariable String id) {
    return Map.of("data", inbox.counts(id));
  }

  @GetMapping
  public Map<String, Object> list(
      @PathVariable String id,
      @RequestParam(required = false) String filter,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return Map.of("data", inbox.page(id, filter, cursor, limit));
  }
}
