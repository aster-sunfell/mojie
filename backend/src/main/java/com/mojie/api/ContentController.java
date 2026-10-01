package com.mojie.api;

import com.mojie.content.JdbcContentService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ContentController {
    private final JdbcContentService content;
    private final String owner = System.getenv().getOrDefault("MOJIE_OWNER_ID", "local-author");
    public ContentController(JdbcContentService content) { this.content = content; }
    private static Map<String,Object> data(Object value) { return Map.of("data", value); }
    public record Title(String title) {}
    public record Body(String content, Integer expectedVersion) {}
    public record Setting(String type, String title, String content) {}

    @GetMapping("/projects") public Map<String,Object> projects(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String keyword) {
        return data(content.projectPage(owner, keyword, cursor, limit));
    }
    @PostMapping("/projects") public ResponseEntity<Map<String,Object>> createProject(@RequestBody Title body) {
        return ResponseEntity.status(201).body(data(content.createProject(owner, body.title())));
    }
    @GetMapping("/projects/{id}") public Map<String,Object> project(@PathVariable String id) { return data(content.project(owner, id)); }
    @PatchMapping("/projects/{id}") public Map<String,Object> renameProject(@PathVariable String id, @RequestBody Title body) {
        return data(content.renameProject(owner, id, body.title()));
    }
    @DeleteMapping("/projects/{id}") public ResponseEntity<Void> deleteProject(@PathVariable String id) {
        content.deleteProject(owner, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/projects/{id}/chapters") public Map<String,Object> chapters(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return data(content.chapterPage(owner, id, cursor, limit));
    }
    @PostMapping("/projects/{id}/chapters") public ResponseEntity<Map<String,Object>> createChapter(@PathVariable String id, @RequestBody Title body) {
        return ResponseEntity.status(201).body(data(content.createChapter(owner, id, body.title())));
    }
    @GetMapping("/chapters/{id}") public Map<String,Object> chapter(@PathVariable String id) { return data(content.chapter(owner, id)); }
    @PatchMapping("/chapters/{id}") public Map<String,Object> renameChapter(@PathVariable String id, @RequestBody Title body) {
        return data(content.renameChapter(owner, id, body.title()));
    }
    @PutMapping("/chapters/{id}/content") public Map<String,Object> saveChapter(@PathVariable String id, @RequestBody Body body) {
        if (body.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        return data(content.saveChapter(owner, id, body.content(), body.expectedVersion()));
    }
    @DeleteMapping("/chapters/{id}") public ResponseEntity<Void> deleteChapter(@PathVariable String id) {
        content.deleteChapter(owner, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/chapters/{id}/revisions") public Map<String,Object> revisions(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return data(content.revisionPage(owner, id, cursor, limit));
    }
    @GetMapping("/chapters/{id}/revisions/{version}") public Map<String,Object> revision(@PathVariable String id, @PathVariable int version) {
        return data(content.revision(owner, id, version));
    }
    @GetMapping("/projects/{id}/settings") public Map<String,Object> settings(@PathVariable String id,
            @RequestParam(required = false) String type, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return data(content.settingPage(owner, id, type, keyword, cursor, limit));
    }
    @PostMapping("/projects/{id}/settings") public ResponseEntity<Map<String,Object>> createSetting(@PathVariable String id, @RequestBody Setting body) {
        return ResponseEntity.status(201).body(data(content.createSetting(owner, id, body.type(), body.title(), body.content())));
    }
    @PatchMapping("/settings/{id}") public Map<String,Object> updateSetting(@PathVariable String id, @RequestBody Setting body) {
        return data(content.updateSetting(owner, id, body.type(), body.title(), body.content()));
    }
    @DeleteMapping("/settings/{id}") public ResponseEntity<Void> deleteSetting(@PathVariable String id) {
        content.deleteSetting(owner, id); return ResponseEntity.noContent().build();
    }
}
