package com.mojie.proposal.repository;

import com.mojie.proposal.model.ProposalInbox.*;
import java.util.List;

public interface ProposalInboxRepository {
  Counts counts(String projectId);

  List<Entry> page(String projectId, String filter, String beforeTime, String beforeId, int size);
}
