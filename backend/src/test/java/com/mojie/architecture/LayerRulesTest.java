package com.mojie.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;

class LayerRulesTest {
  static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.mojie");

  @Test
  void interfaceLayerDependsOnBusinessServices() {
    noClasses()
        .that()
        .resideInAnyPackage("..api..", "..common.web..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..repository..",
            "..infrastructure..",
            "java.sql..",
            "javax.sql..",
            "org.springframework.jdbc..",
            "org.springframework.transaction..")
        .check(CLASSES);
  }

  @Test
  void servicesUsePortsAndRepositories() {
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..api..",
            "..repository.jdbc..",
            "..infrastructure..",
            "java.sql..",
            "javax.sql..",
            "org.springframework.jdbc..")
        .check(CLASSES);
  }

  @Test
  void repositoryContractsHideTechnologyAndReturnTypedRows() {
    noClasses()
        .that()
        .resideInAPackage("..repository")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "java.sql..",
            "javax.sql..",
            "org.springframework.web..",
            "org.springframework.jdbc..",
            "..repository.jdbc..")
        .check(CLASSES);
    for (JavaClass type : CLASSES)
      if (type.isInterface() && type.getPackageName().endsWith(".repository")) {
        for (JavaMethod method : type.getMethods()) {
          assertFalse(
              method.getReturnType().getName().contains("java.util.Map"), method.getFullName());
          assertFalse(
              method.getReturnType().getName().contains("java.lang.Object"), method.getFullName());
        }
      }
  }

  @Test
  void jdbcAdaptersDoNotCallBusinessServices() {
    noClasses()
        .that()
        .resideInAPackage("..repository.jdbc..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..api..", "..service..")
        .check(CLASSES);
  }

  @Test
  void algorithmsAndModelsStayPortable() {
    noClasses()
        .that()
        .resideInAnyPackage("..service.loop..", "..model..")
        .and()
        .resideOutsideOfPackage("..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "..infrastructure..",
            "..repository..")
        .check(CLASSES);
    noClasses()
        .that()
        .haveSimpleName("AgentTool")
        .or()
        .haveSimpleName("ToolContentReader")
        .or()
        .haveSimpleName("ToolContext")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "..infrastructure..",
            "..repository..")
        .check(CLASSES);
  }

  @Test
  void providerSdkIsConfinedToItsAdapter() {
    noClasses()
        .that()
        .resideOutsideOfPackage("..infrastructure.model.anthropic..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.anthropic..")
        .check(CLASSES);
  }

  @Test
  void onlyConfigurationConstructsBusinessAndRuntimeComponents() {
    for (JavaClass type : CLASSES)
      if (type.getPackageName().contains(".service")) {
        for (JavaConstructorCall call : type.getConstructorCallsFromSelf()) {
          String target = call.getTargetOwner().getPackageName();
          assertFalse(
              target.contains(".repository.jdbc") || target.contains(".infrastructure"),
              call.getDescription());
          assertFalse(
              call.getTargetOwner()
                  .getName()
                  .matches(
                      "java\\.util\\.concurrent\\.(ThreadPoolExecutor|ScheduledThreadPoolExecutor)"),
              call.getDescription());
        }
      }
  }
}
