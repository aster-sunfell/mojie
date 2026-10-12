package com.mojie.conversation.repository;

import com.mojie.conversation.model.ConversationModels.*;
import java.util.List;

public interface ConversationRepository {
  Conversation get(String id, boolean lock);

  Conversation insert(String project, String model, String config);

  List<Conversation> page(String project, String afterTime, String afterId, int size);

  int patch(String id, long version, String title, boolean discovery);

  List<DisplayMessage> messages(String id, long after, int size);

  DisplayMessage message(String conversation, String id);

  Existing existing(String client);

  String insertMessage(String conversation, long seq, String content);

  String insertRun(
      String project,
      String conversation,
      String message,
      long boundary,
      String task,
      String chapter,
      Long version,
      String base,
      String contextChapter,
      Long contextVersion,
      String contextContent,
      String content,
      String key,
      String digest,
      String promptHash,
      String schemaHash,
      String model,
      String config,
      String process,
      String retry,
      boolean analysisOnly);

  int occupy(String conversation, long expected, String run, boolean retry, String title);

  void pins(String conversation, List<Selection> selections);

  List<Selection> pins(String conversation);

  RunContext runContext(String run);

  List<Protocol> history(String conversation, long boundary, long after);

  Summary summary(String conversation, long boundary);

  void saveSummary(
      String conversation,
      String run,
      long boundary,
      String content,
      String parent,
      String model,
      int call);
}
