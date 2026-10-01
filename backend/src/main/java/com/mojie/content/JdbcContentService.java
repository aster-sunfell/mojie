package com.mojie.content;

import com.mojie.common.Page;
import com.mojie.common.PageCursor;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Author-facing content writes. Directory and setting mutations serialize with run admission on project. */
public final class JdbcContentService {
    private final DataSource dataSource;
    private final PageCursor cursors = PageCursor.configured();
    public JdbcContentService(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public static final class ContentException extends RuntimeException {
        private final String code;
        public ContentException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    private static ContentException error(String code, String message) { return new ContentException(code, message); }
    private static String text(String value, String field, int min, int max) {
        if (value == null) throw error("VALIDATION_ERROR", "Invalid " + field);
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int length = normalized.codePointCount(0, normalized.length());
        if (length < min || length > max || (min > 0 && normalized.isBlank()))
            throw error("VALIDATION_ERROR", "Invalid " + field);
        return normalized;
    }
    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException e) { throw error("VALIDATION_ERROR", "Invalid ID"); }
    }
    private static String type(String value) {
        if (!Set.of("CHARACTER", "WORLD", "RULE").contains(value)) throw error("VALIDATION_ERROR", "Invalid setting type");
        return value;
    }
    private static int limit(Integer value) {
        if (value == null) return 20;
        if (value < 1 || value > 50) throw error("VALIDATION_ERROR", "Invalid limit");
        return value;
    }
    private Page<Map<String,Object>> page(List<Map<String,Object>> rows, int size, String scope,
                                           java.util.function.Function<Map<String,Object>, String> marker) {
        if (rows.size() <= size) return new Page<>(rows, null);
        List<Map<String,Object>> items = rows.subList(0, size);
        return new Page<>(items, cursors.encode(scope, marker.apply(items.getLast())));
    }
    private static String[] pair(String marker) {
        if (marker == null) return new String[]{null, null};
        String[] parts = marker.split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
        return parts;
    }
    private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T db(boolean write, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            if (write) c.setAutoCommit(false);
            try {
                T result = work.run(c);
                if (write) c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                if (write) c.rollback();
                if (e instanceof ContentException ce) throw ce;
                throw new IllegalStateException("Content database operation failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Content database unavailable", e); }
    }
    private static PreparedStatement sql(Connection c, String query, Object... args) throws SQLException {
        PreparedStatement s = c.prepareStatement(query);
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
        return s;
    }
    private static Map<String,Object> row(ResultSet rs) throws SQLException {
        Map<String,Object> value = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String key = meta.getColumnLabel(i);
            String[] parts = key.split("_");
            StringBuilder camel = new StringBuilder(parts[0]);
            for (int j = 1; j < parts.length; j++) camel.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
            Object item = rs.getObject(i);
            if (item instanceof Timestamp timestamp) item = timestamp.toInstant().toString();
            else if (item instanceof UUID || item instanceof OffsetDateTime) item = item.toString();
            value.put(camel.toString(), item);
        }
        return value;
    }
    private static Map<String,Object> one(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, query, args); ResultSet rs = s.executeQuery()) {
            if (!rs.next()) throw error("RESOURCE_NOT_FOUND", "Resource unavailable");
            return row(rs);
        }
    }
    private static List<Map<String,Object>> many(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, query, args); ResultSet rs = s.executeQuery()) {
            List<Map<String,Object>> items = new ArrayList<>();
            while (rs.next()) items.add(row(rs));
            return items;
        }
    }
    private static String insertId(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, query, args); ResultSet rs = s.executeQuery()) { rs.next(); return rs.getString(1); }
    }
    private static void change(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, query, args)) { if (s.executeUpdate() != 1) throw error("RESOURCE_NOT_FOUND", "Resource unavailable"); }
    }
    private static void lockProject(Connection c, String owner, String projectId, boolean requireIdle) throws SQLException {
        Map<String,Object> project = one(c, "SELECT id, active_run_id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL FOR UPDATE", projectId, owner);
        if (requireIdle && project.get("activeRunId") != null) throw error("RESOURCE_BUSY", "Project has an active run");
    }
    private static void noPending(Connection c, String where, String id) throws SQLException {
        try (PreparedStatement s = sql(c, "SELECT 1 FROM tb_edit_proposal WHERE status = 'PENDING' AND " + where + " = ?::uuid LIMIT 1", id);
             ResultSet rs = s.executeQuery()) {
            if (rs.next()) throw error("RESOURCE_BUSY", "Resource has a pending proposal");
        }
    }

    public List<Map<String,Object>> projects(String owner, Integer requestedLimit) {
        return projectPage(owner, null, requestedLimit).items();
    }
    public Page<Map<String,Object>> projectPage(String owner, String cursor, Integer requestedLimit) {
        return projectPage(owner, null, cursor, requestedLimit);
    }
    public Page<Map<String,Object>> projectPage(String owner, String keyword, String cursor, Integer requestedLimit) {
        int size = limit(requestedLimit);
        String query = keyword == null ? "" : text(keyword.trim(), "keyword", 0, 80);
        String scope = "projects:" + owner + (query.isEmpty() ? "" : ":keyword:" + query);
        String[] marker = pair(cursors.decode(scope, cursor));
        if (marker[0] != null) {
            try { OffsetDateTime.parse(marker[0]); UUID.fromString(marker[1]); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor", e); }
        }
        return db(false, c -> page(many(c, """
                SELECT id, name AS title, updated_at FROM tb_project
                 WHERE owner_id = ? AND deleted_at IS NULL
                   AND strpos(lower(name), lower(?)) > 0
                   AND (?::timestamptz IS NULL OR (updated_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY updated_at DESC, id DESC LIMIT ?
                """, owner, query, marker[0], marker[0], marker[1], size + 1), size, scope,
                item -> item.get("updatedAt") + "|" + item.get("id")));
    }
    public Map<String,Object> project(String owner, String id) {
        String projectId = uuid(id);
        return db(false, c -> one(c, "SELECT id, name AS title, active_run_id, created_at, updated_at FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL", projectId, owner));
    }
    public Map<String,Object> createProject(String owner, String title) {
        String name = text(title, "title", 1, 80);
        return db(true, c -> {
            String id = insertId(c, "INSERT INTO tb_project(owner_id, name) VALUES (?, ?) RETURNING id", owner, name);
            return one(c, "SELECT id, name AS title, active_run_id, created_at, updated_at FROM tb_project WHERE id = ?::uuid", id);
        });
    }
    public Map<String,Object> renameProject(String owner, String id, String title) {
        String projectId = uuid(id), name = text(title, "title", 1, 80);
        return db(true, c -> {
            lockProject(c, owner, projectId, false);
            change(c, "UPDATE tb_project SET name = ?, updated_at = now() WHERE id = ?::uuid", name, projectId);
            return one(c, "SELECT id, name AS title, active_run_id, created_at, updated_at FROM tb_project WHERE id = ?::uuid", projectId);
        });
    }
    public void deleteProject(String owner, String id) {
        String projectId = uuid(id);
        db(true, c -> {
            lockProject(c, owner, projectId, true); noPending(c, "project_id", projectId);
            change(c, "UPDATE tb_project SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid", projectId);
            return null;
        });
    }
    public List<Map<String,Object>> chapters(String owner, String id, Integer requestedLimit) {
        return chapterPage(owner, id, null, requestedLimit).items();
    }
    public Page<Map<String,Object>> chapterPage(String owner, String id, String cursor, Integer requestedLimit) {
        String projectId = uuid(id); int size = limit(requestedLimit);
        String scope = "chapters:" + owner + ":" + projectId;
        String[] marker = pair(cursors.decode(scope, cursor));
        Integer order = null;
        if (marker[0] != null) {
            try { order = Integer.parseInt(marker[0]); UUID.fromString(marker[1]); if (order < 1) throw new IllegalArgumentException(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor"); }
        }
        final Integer afterOrder = order;
        return db(false, c -> {
            one(c, "SELECT id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL", projectId, owner);
            return page(many(c, """
                    SELECT id, project_id, chapter_order, title, version FROM tb_chapter
                     WHERE project_id = ?::uuid AND deleted_at IS NULL
                       AND (?::integer IS NULL OR (chapter_order, id) > (?, ?::uuid))
                     ORDER BY chapter_order, id LIMIT ?
                    """, projectId, afterOrder, afterOrder, marker[1], size + 1), size, scope,
                    item -> item.get("chapterOrder") + "|" + item.get("id"));
        });
    }
    public Map<String,Object> chapter(String owner, String id) {
        String chapterId = uuid(id);
        return db(false, c -> one(c, "SELECT ch.id, ch.project_id, ch.chapter_order, ch.title, ch.content, ch.version, ch.updated_at FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.owner_id = ? AND p.deleted_at IS NULL", chapterId, owner));
    }
    public Map<String,Object> createChapter(String owner, String id, String title) {
        String projectId = uuid(id), name = text(title, "title", 1, 120);
        return db(true, c -> {
            lockProject(c, owner, projectId, true);
            Map<String,Object> order = one(c, "UPDATE tb_project SET next_chapter_order = next_chapter_order + 1, updated_at = now() WHERE id = ?::uuid RETURNING next_chapter_order - 1 AS chapter_order", projectId);
            String chapterId = insertId(c, "INSERT INTO tb_chapter(project_id, chapter_order, title) VALUES (?::uuid, ?, ?) RETURNING id", projectId, order.get("chapterOrder"), name);
            change(c, "INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES (?::uuid, 1, '', 'CREATE')", chapterId);
            return one(c, "SELECT id, project_id, chapter_order, title, content, version FROM tb_chapter WHERE id = ?::uuid", chapterId);
        });
    }
    public Map<String,Object> renameChapter(String owner, String id, String title) {
        String chapterId = uuid(id), name = text(title, "title", 1, 120);
        return db(true, c -> {
            String projectId = String.valueOf(one(c, "SELECT ch.project_id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id = ?::uuid AND p.owner_id = ? AND ch.deleted_at IS NULL AND p.deleted_at IS NULL", chapterId, owner).get("projectId"));
            lockProject(c, owner, projectId, true);
            change(c, "UPDATE tb_chapter SET title = ?, updated_at = now() WHERE id = ?::uuid AND deleted_at IS NULL", name, chapterId);
            return one(c, "SELECT id, project_id, chapter_order, title, content, version FROM tb_chapter WHERE id = ?::uuid", chapterId);
        });
    }
    public Map<String,Object> saveChapter(String owner, String id, String content, int expectedVersion) {
        String chapterId = uuid(id), body = text(content, "content", 0, 6000);
        if (expectedVersion < 1) throw error("VALIDATION_ERROR", "Invalid expectedVersion");
        return db(true, c -> {
            Map<String,Object> old = one(c, "SELECT ch.id, ch.version, ch.content FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.owner_id = ? AND p.deleted_at IS NULL FOR UPDATE OF ch", chapterId, owner);
            if (((Number) old.get("version")).intValue() != expectedVersion) throw error("VERSION_CONFLICT", "Chapter version changed");
            if (body.equals(old.get("content")))
                return one(c, "SELECT id, project_id, chapter_order, title, content, version, updated_at FROM tb_chapter WHERE id = ?::uuid", chapterId);
            change(c, "UPDATE tb_chapter SET content = ?, version = version + 1, updated_at = now() WHERE id = ?::uuid AND version = ?", body, chapterId, expectedVersion);
            change(c, "INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES (?::uuid, ?, ?, 'MANUAL')", chapterId, expectedVersion + 1, body);
            return one(c, "SELECT id, project_id, chapter_order, title, content, version, updated_at FROM tb_chapter WHERE id = ?::uuid", chapterId);
        });
    }
    public void deleteChapter(String owner, String id) {
        String chapterId = uuid(id);
        db(true, c -> {
            String projectId = String.valueOf(one(c, "SELECT ch.project_id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.owner_id = ? AND p.deleted_at IS NULL", chapterId, owner).get("projectId"));
            lockProject(c, owner, projectId, true); noPending(c, "target_chapter_id", chapterId);
            change(c, "UPDATE tb_chapter SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid AND deleted_at IS NULL", chapterId);
            return null;
        });
    }
    public List<Map<String,Object>> revisions(String owner, String id, Integer requestedLimit) {
        return revisionPage(owner, id, null, requestedLimit).items();
    }
    public Page<Map<String,Object>> revisionPage(String owner, String id, String cursor, Integer requestedLimit) {
        String chapterId = uuid(id); int size = limit(requestedLimit);
        String scope = "revisions:" + owner + ":" + chapterId;
        String marker = cursors.decode(scope, cursor);
        Integer before = null;
        if (marker != null) {
            try { before = Integer.parseInt(marker); if (before < 1) throw new IllegalArgumentException(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor"); }
        }
        final Integer beforeVersion = before;
        return db(false, c -> {
            one(c, "SELECT ch.id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id = ?::uuid AND p.owner_id = ?", chapterId, owner);
            return page(many(c, """
                    SELECT version, source, proposal_id, created_at FROM tb_chapter_revision
                     WHERE chapter_id = ?::uuid AND (?::integer IS NULL OR version < ?)
                     ORDER BY version DESC LIMIT ?
                    """, chapterId, beforeVersion, beforeVersion, size + 1), size, scope,
                    item -> item.get("version").toString());
        });
    }
    public Map<String,Object> revision(String owner, String id, int version) {
        String chapterId = uuid(id);
        return db(false, c -> one(c, "SELECT r.chapter_id, r.version, r.content, r.source, r.proposal_id, r.created_at FROM tb_chapter_revision r JOIN tb_chapter ch ON ch.id = r.chapter_id JOIN tb_project p ON p.id = ch.project_id WHERE r.chapter_id = ?::uuid AND r.version = ? AND p.owner_id = ?", chapterId, version, owner));
    }
    public List<Map<String,Object>> settings(String owner, String id, String filterType, String keyword, Integer requestedLimit) {
        return settingPage(owner, id, filterType, keyword, null, requestedLimit).items();
    }
    public Page<Map<String,Object>> settingPage(String owner, String id, String filterType, String keyword,
                                                 String cursor, Integer requestedLimit) {
        String projectId = uuid(id); int size = limit(requestedLimit);
        if (filterType != null) type(filterType);
        String scope = "settings:" + owner + ":" + projectId + ":" + filterType + ":" + keyword;
        String marker = cursors.decode(scope, cursor);
        if (marker != null) {
            try { UUID.fromString(marker); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor"); }
        }
        return db(false, c -> {
            one(c, "SELECT id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL", projectId, owner);
            return page(many(c, """
                    SELECT id, project_id, type, title, content, updated_at FROM tb_setting
                     WHERE project_id = ?::uuid AND deleted_at IS NULL
                       AND (?::text IS NULL OR type = ?)
                       AND (?::text IS NULL OR position(? in title) > 0 OR position(? in content) > 0)
                       AND (?::uuid IS NULL OR id > ?::uuid)
                     ORDER BY id LIMIT ?
                    """, projectId, filterType, filterType, keyword, keyword, keyword, marker, marker, size + 1),
                    size, scope, item -> item.get("id").toString());
        });
    }
    public Map<String,Object> createSetting(String owner, String id, String settingType, String title, String content) {
        String projectId = uuid(id), kind = type(settingType), name = text(title, "title", 1, 80), body = text(content, "content", 1, 2000);
        return db(true, c -> {
            lockProject(c, owner, projectId, true);
            String settingId = insertId(c, "INSERT INTO tb_setting(project_id, type, title, content) VALUES (?::uuid, ?, ?, ?) RETURNING id", projectId, kind, name, body);
            return one(c, "SELECT id, project_id, type, title, content, updated_at FROM tb_setting WHERE id = ?::uuid", settingId);
        });
    }
    public Map<String,Object> updateSetting(String owner, String id, String settingType, String title, String content) {
        String settingId = uuid(id), kind = type(settingType), name = text(title, "title", 1, 80), body = text(content, "content", 1, 2000);
        return db(true, c -> {
            String projectId = String.valueOf(one(c, "SELECT s.project_id FROM tb_setting s JOIN tb_project p ON p.id = s.project_id WHERE s.id = ?::uuid AND s.deleted_at IS NULL AND p.owner_id = ? AND p.deleted_at IS NULL", settingId, owner).get("projectId"));
            lockProject(c, owner, projectId, true);
            change(c, "UPDATE tb_setting SET type = ?, title = ?, content = ?, updated_at = now() WHERE id = ?::uuid AND deleted_at IS NULL", kind, name, body, settingId);
            return one(c, "SELECT id, project_id, type, title, content, updated_at FROM tb_setting WHERE id = ?::uuid", settingId);
        });
    }
    public void deleteSetting(String owner, String id) {
        String settingId = uuid(id);
        db(true, c -> {
            String projectId = String.valueOf(one(c, "SELECT s.project_id FROM tb_setting s JOIN tb_project p ON p.id = s.project_id WHERE s.id = ?::uuid AND s.deleted_at IS NULL AND p.owner_id = ? AND p.deleted_at IS NULL", settingId, owner).get("projectId"));
            lockProject(c, owner, projectId, true);
            change(c, "UPDATE tb_setting SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid AND deleted_at IS NULL", settingId);
            return null;
        });
    }
}
