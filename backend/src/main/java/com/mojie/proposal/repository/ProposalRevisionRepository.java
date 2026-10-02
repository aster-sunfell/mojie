package com.mojie.proposal.repository;

import java.util.*;

public interface ProposalRevisionRepository {
  int link(String chapterId, int version, String proposalId, String decisions);
}
