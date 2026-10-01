package com.mojie.proposal.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojie.content.JdbcContentService.ContentException;
import com.mojie.proposal.ProposalDiff;
import java.sql.*;
import java.util.*;

/** Review choices and chapter text commit together, using the content save's transaction. */
public final class JdbcProposalReview {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JdbcProposalReview() {}
    public record Choice(Integer itemId, String status, String editedContent) {
        public Choice(Integer itemId, String status) { this(itemId, status, null); }
    }
    public record Review(String proposalId, List<Choice> decisions) {}
    public record Saved(String proposalId, String decisions, boolean applied) {}

    public static List<Map<String,Object>> items(String base, String proposed, String status,
                                                 Integer appliedVersion, JsonNode decisions) {
        List<Map<String,Object>> items = new ArrayList<>();
        for (ProposalDiff.Item item : ProposalDiff.between(base, proposed)) {
            JsonNode decision = decisions == null ? null : decisions.get(String.valueOf(item.id()));
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("id", item.id()); value.put("start", item.start()); value.put("end", item.end());
            value.put("lineNumber", item.lineNumber()); value.put("before", item.before()); value.put("after", item.after());
            value.put("contextBefore", item.contextBefore()); value.put("contextAfter", item.contextAfter());
            value.put("status", decision != null ? decision.path("status").asText()
                    : "APPLIED".equals(status) ? "APPLIED" : "REJECTED".equals(status) ? "IGNORED" : "PENDING");
            value.put("savedVersion", decision != null && decision.has("savedVersion")
                    ? Integer.valueOf(decision.path("savedVersion").asInt()) : appliedVersion);
            if (decision != null && decision.has("editedContent")) value.put("editedContent", decision.path("editedContent").asText());
            items.add(value);
        }
        return items;
    }

    public static List<Saved> save(Connection c, String owner, String chapterId, int version,
                                    List<Review> reviews) throws SQLException {
        if (reviews == null || reviews.isEmpty()) return List.of();
        if (reviews.size() > 50) throw invalid();
        Set<String> seen = new HashSet<>();
        List<Saved> saved = new ArrayList<>();
        for (Review review : reviews) {
            if (review == null || review.proposalId() == null || review.decisions() == null
                    || review.decisions().isEmpty() || !seen.add(review.proposalId())) throw invalid();
            try { UUID.fromString(review.proposalId()); } catch (IllegalArgumentException e) { throw invalid(); }
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT p.base_content, p.proposed_content, p.status, p.review_decisions
                      FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
                     WHERE p.id = ?::uuid AND p.target_chapter_id = ?::uuid
                       AND r.owner_id = ? AND r.status = 'SUCCEEDED' FOR UPDATE OF p
                    """)) {
                s.setString(1, review.proposalId()); s.setString(2, chapterId); s.setString(3, owner);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) throw new ContentException("RESOURCE_NOT_FOUND", "Proposal unavailable");
                    int count = ProposalDiff.between(rs.getString(1), rs.getString(2)).size();
                    ObjectNode decisions = decode(rs.getString(4));
                    ObjectNode delta = JSON.createObjectNode();
                    boolean applied = false;
                    Set<Integer> ids = new HashSet<>();
                    for (Choice choice : review.decisions()) {
                        if (choice == null || choice.itemId() == null || choice.itemId() < 0 || choice.itemId() >= count
                                || !ids.add(choice.itemId()) || !("APPLIED".equals(choice.status()) || "IGNORED".equals(choice.status())))
                            throw invalid();
                        String key = String.valueOf(choice.itemId());
                        String edited = choice.editedContent() == null ? null
                                : choice.editedContent().replace("\r\n", "\n").replace('\r', '\n');
                        if (edited != null && (!"APPLIED".equals(choice.status())
                                || edited.codePointCount(0, edited.length()) > 6000)) throw invalid();
                        JsonNode existing = decisions.get(key);
                        if (existing != null && existing.path("status").asText().equals(choice.status())
                                && (edited == null || edited.equals(existing.path("editedContent").asText()))) continue;
                        if (existing != null || Set.of("APPLIED", "REJECTED").contains(rs.getString(3)))
                            throw new ContentException("PROPOSAL_STATE_CONFLICT", "Proposal choice changed");
                        ObjectNode value = JSON.createObjectNode().put("status", choice.status()).put("savedVersion", version);
                        if (edited != null) value.put("editedContent", edited);
                        decisions.set(key, value); delta.set(key, value);
                        applied |= "APPLIED".equals(choice.status());
                    }
                    if (delta.isEmpty()) continue;
                    boolean anyApplied = false;
                    for (JsonNode decision : decisions) anyApplied |= "APPLIED".equals(decision.path("status").asText());
                    String status = decisions.size() == count ? anyApplied ? "APPLIED" : "REJECTED"
                            : anyApplied ? "PARTIALLY_APPLIED" : "PENDING";
                    try (PreparedStatement update = c.prepareStatement("""
                            UPDATE tb_edit_proposal SET review_decisions = ?::jsonb, status = ?,
                                   applied_version = ?, updated_at = now() WHERE id = ?::uuid
                            """)) {
                        update.setString(1, decisions.toString()); update.setString(2, status);
                        if ("APPLIED".equals(status)) update.setInt(3, version); else update.setNull(3, Types.INTEGER);
                        update.setString(4, review.proposalId()); update.executeUpdate();
                    }
                    saved.add(new Saved(review.proposalId(), delta.toString(), applied));
                }
            }
        }
        return saved;
    }
    public static void link(Connection c, String chapterId, int version, List<Saved> reviews) throws SQLException {
        for (Saved review : reviews) try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO tb_chapter_revision_review(chapter_id, version, proposal_id, decisions)
                VALUES (?::uuid, ?, ?::uuid, ?::jsonb)
                ON CONFLICT (chapter_id, version, proposal_id)
                DO UPDATE SET decisions = tb_chapter_revision_review.decisions || EXCLUDED.decisions
                """)) {
            s.setString(1, chapterId); s.setInt(2, version); s.setString(3, review.proposalId());
            s.setString(4, review.decisions()); s.executeUpdate();
        }
    }
    private static ObjectNode decode(String value) {
        try { return (ObjectNode) JSON.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("Stored proposal review is invalid", e); }
    }
    private static ContentException invalid() { return new ContentException("VALIDATION_ERROR", "Invalid proposal review"); }
}
