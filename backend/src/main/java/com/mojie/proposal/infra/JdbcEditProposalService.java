package com.mojie.proposal.infra;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojie.proposal.ProposalDiff;
import java.sql.*;
import java.util.Objects;
import javax.sql.DataSource;

/** Proposal decisions are separate from the already successful Agent run. */
public final class JdbcEditProposalService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DataSource dataSource;
    public JdbcEditProposalService(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public record Decision(String status, Integer appliedVersion) {}
    private record Proposal(String id, String projectId, String targetChapterId, int baseVersion,
                            String proposedContent, String status, Integer appliedVersion,
                            String baseContent, String reviewDecisions) {}
    public static final class ProposalException extends RuntimeException {
        private final String code;
        public ProposalException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** Returns CONFLICTED after committing that status; the HTTP adapter maps it to 409. */
    public Decision accept(String ownerId, String proposalId) {
        return transaction(connection -> {
            String projectId = projectId(connection, ownerId, proposalId);
            lockProject(connection, projectId);
            Proposal proposal = lockProposal(connection, ownerId, proposalId);
            if ("APPLIED".equals(proposal.status())) return new Decision("APPLIED", proposal.appliedVersion());
            if (!"PENDING".equals(proposal.status())) throw stateConflict();
            if (!"{}".equals(proposal.reviewDecisions())) throw stateConflict();
            int currentVersion;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT version FROM tb_chapter WHERE id = ?::uuid AND project_id = ?::uuid
                      AND deleted_at IS NULL FOR UPDATE
                    """)) {
                statement.setString(1, proposal.targetChapterId()); statement.setString(2, projectId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new ProposalException("RESOURCE_NOT_FOUND", "Chapter unavailable");
                    currentVersion = rows.getInt(1);
                }
            }
            if (currentVersion != proposal.baseVersion()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_edit_proposal SET status = 'CONFLICTED', updated_at = now()
                         WHERE id = ?::uuid AND status = 'PENDING'
                        """)) {
                    statement.setString(1, proposalId); requireOne(statement.executeUpdate());
                }
                return new Decision("CONFLICTED", null);
            }
            int nextVersion;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_chapter SET content = ?, version = version + 1, updated_at = now()
                     WHERE id = ?::uuid AND project_id = ?::uuid AND version = ? AND deleted_at IS NULL
                     RETURNING version
                    """)) {
                statement.setString(1, proposal.proposedContent());
                statement.setString(2, proposal.targetChapterId()); statement.setString(3, projectId);
                statement.setInt(4, proposal.baseVersion());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Chapter version changed under lock");
                    nextVersion = rows.getInt(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tb_chapter_revision(chapter_id, version, content, source, proposal_id)
                    VALUES (?::uuid, ?, ?, 'AGENT', ?::uuid)
                    """)) {
                statement.setString(1, proposal.targetChapterId()); statement.setInt(2, nextVersion);
                statement.setString(3, proposal.proposedContent()); statement.setString(4, proposalId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_edit_proposal SET status = 'APPLIED', applied_version = ?, updated_at = now()
                     WHERE id = ?::uuid AND status = 'PENDING'
                    """)) {
                statement.setInt(1, nextVersion); statement.setString(2, proposalId);
                requireOne(statement.executeUpdate());
            }
            return new Decision("APPLIED", nextVersion);
        });
    }

    public Decision reject(String ownerId, String proposalId) {
        return transaction(connection -> {
            String projectId = projectId(connection, ownerId, proposalId);
            lockProject(connection, projectId);
            Proposal proposal = lockProposal(connection, ownerId, proposalId);
            if ("REJECTED".equals(proposal.status())) return new Decision("REJECTED", null);
            if (!("PENDING".equals(proposal.status()) || "PARTIALLY_APPLIED".equals(proposal.status())
                    || "CONFLICTED".equals(proposal.status()))) throw stateConflict();
            ObjectNode decisions;
            try { decisions = (ObjectNode) JSON.readTree(proposal.reviewDecisions()); }
            catch (Exception e) { throw new IllegalStateException("Invalid review decisions", e); }
            Integer appliedVersion = null;
            for (var entry : decisions) if ("APPLIED".equals(entry.path("status").asText()))
                appliedVersion = Math.max(appliedVersion == null ? 0 : appliedVersion, entry.path("savedVersion").asInt());
            for (var item : ProposalDiff.between(proposal.baseContent(), proposal.proposedContent()))
                if (!decisions.has(String.valueOf(item.id()))) decisions.set(String.valueOf(item.id()),
                        JSON.createObjectNode().put("status", "IGNORED"));
            String status = appliedVersion == null ? "REJECTED" : "APPLIED";
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_edit_proposal SET status = ?, applied_version = ?, review_decisions = ?::jsonb, updated_at = now()
                     WHERE id = ?::uuid
                    """)) {
                statement.setString(1, status); statement.setObject(2, appliedVersion);
                statement.setString(3, decisions.toString()); statement.setString(4, proposalId); requireOne(statement.executeUpdate());
            }
            return new Decision(status, appliedVersion);
        });
    }

    private static String projectId(Connection connection, String ownerId, String proposalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.project_id FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
                 WHERE p.id = ?::uuid AND r.owner_id = ? AND r.status = 'SUCCEEDED'
                """)) {
            statement.setString(1, proposalId); statement.setString(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new ProposalException("RESOURCE_NOT_FOUND", "Proposal unavailable");
                return rows.getString(1);
            }
        }
    }
    private static void lockProject(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM tb_project WHERE id = ?::uuid AND deleted_at IS NULL FOR UPDATE
                """)) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new ProposalException("RESOURCE_NOT_FOUND", "Project unavailable");
            }
        }
    }
    private static Proposal lockProposal(Connection connection, String ownerId, String proposalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id, p.project_id, p.target_chapter_id, p.base_version,
                       p.proposed_content, p.status, p.applied_version, p.base_content, p.review_decisions
                  FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
                 WHERE p.id = ?::uuid AND r.owner_id = ? AND r.status = 'SUCCEEDED'
                 FOR UPDATE OF p
                """)) {
            statement.setString(1, proposalId); statement.setString(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new ProposalException("RESOURCE_NOT_FOUND", "Proposal unavailable");
                int applied = rows.getInt(7);
                boolean appliedWasNull = rows.wasNull();
                return new Proposal(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4),
                        rows.getString(5), rows.getString(6), appliedWasNull ? null : applied,
                        rows.getString(8), rows.getString(9));
            }
        }
    }
    private static ProposalException stateConflict() {
        return new ProposalException("PROPOSAL_STATE_CONFLICT", "Proposal is no longer pending");
    }
    private static void requireOne(int count) { if (count != 1) throw new IllegalStateException("Proposal changed concurrently"); }
    private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof ProposalException proposal) throw proposal;
                throw new IllegalStateException("Proposal transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Proposal database unavailable", e); }
    }
}
