package com.mojie.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModuleRulesTest {
  @Test
  void contentAndCommonHaveNoBusinessBackEdges() {
    noClasses()
        .that()
        .resideInAnyPackage("..content.service..", "..content.repository..", "..content.model..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..proposal..", "..agent..", "..system..")
        .check(LayerRulesTest.CLASSES);
    noClasses()
        .that()
        .resideInAPackage("..common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..content..",
            "..agent..",
            "..proposal..",
            "..system..",
            "..infrastructure..",
            "..config..")
        .check(LayerRulesTest.CLASSES);
  }

  @Test
  void businessModuleDependenciesAreAcyclic() {
    Set<String> modules = Set.of("content", "agent", "proposal", "system");
    Map<String, Set<String>> graph = new HashMap<>();
    for (String module : modules) graph.put(module, new HashSet<>());
    for (JavaClass type : LayerRulesTest.CLASSES) {
      String from = module(type, modules);
      if (from == null || type.getPackageName().contains(".api")) continue;
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        String to = module(dependency.getTargetClass(), modules);
        if (to != null && !from.equals(to)) graph.get(from).add(to);
      }
    }
    for (String module : modules) visit(module, graph, new HashSet<>(), new HashSet<>());
  }

  private static String module(JavaClass type, Set<String> modules) {
    for (String module : modules)
      if (type.getPackageName().startsWith("com.mojie." + module + ".")) return module;
    return null;
  }

  private static void visit(
      String module, Map<String, Set<String>> graph, Set<String> visiting, Set<String> done) {
    if (done.contains(module)) return;
    assertTrue(visiting.add(module), "Business module cycle at " + module + ": " + graph);
    for (String dependency : graph.get(module)) visit(dependency, graph, visiting, done);
    visiting.remove(module);
    done.add(module);
  }

  @Test
  void onlyChapterServiceWritesOfficialContentAndHistory() {
    for (JavaClass type : LayerRulesTest.CLASSES) {
      for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
        String owner = call.getTargetOwner().getName(), method = call.getTarget().getName();
        if ((owner.equals("com.mojie.content.repository.ChapterRepository")
                && method.equals("updateContentIfVersionMatches"))
            || (owner.equals("com.mojie.content.repository.RevisionRepository")
                && Set.of("insert", "insertCreate").contains(method)))
          assertEquals(
              "com.mojie.content.service.ChapterService", type.getName(), call.getDescription());
      }
    }
  }
}
