package com.flashsale;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.flashsale";
    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
        new ClassFileImporter().importPackages(BASE_PACKAGE);

    @Test
    void domainPackagesDoNotDependOnAdapterPackages() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");
        rule.check(CLASSES);
    }

    @Test
    void domainPackagesDoNotDependOnSpringFramework() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");
        rule.check(CLASSES);
    }

    @Test
    void modulesDoNotReachIntoOtherModulesAdapterPackages() {
        for (String module : new String[]{"identity", "catalog", "flashsale", "inventory", "order", "payment", "notification", "admin"}) {
            ArchRule rule = noClasses().that().resideInAPackage(BASE_PACKAGE + "." + module + "..")
                .and().resideOutsideOfPackage(BASE_PACKAGE + "." + module + ".adapter..")
                .should().dependOnClassesThat().resideInAnyPackage(otherModulesAdapterPackages(module))
                .allowEmptyShould(module.equals("admin"));
            rule.check(CLASSES);
        }
    }

    private String[] otherModulesAdapterPackages(String exclude) {
        return java.util.Arrays.stream(new String[]{"identity", "catalog", "flashsale", "inventory", "order", "payment", "notification", "admin"})
            .filter(m -> !m.equals(exclude))
            .map(m -> BASE_PACKAGE + "." + m + ".adapter..")
            .toArray(String[]::new);
    }
}
