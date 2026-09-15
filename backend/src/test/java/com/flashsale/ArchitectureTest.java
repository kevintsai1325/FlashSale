package com.flashsale;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.flashsale";

    /**
     * P5 之後 order / inventory / payment 不在這個服務裡了 —— 它們是 order-service。
     * 這份清單因此也是「platform 目前還擁有什麼」的權威說明：清單縮短就是拆分的進度。
     */
    private static final String[] MODULES = {"identity", "catalog", "flashsale", "notification", "admin"};
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
        for (String module : MODULES) {
            ArchRule rule = noClasses().that().resideInAPackage(BASE_PACKAGE + "." + module + "..")
                .and().resideOutsideOfPackage(BASE_PACKAGE + "." + module + ".adapter..")
                .should().dependOnClassesThat().resideInAnyPackage(otherModulesAdapterPackages(module));
            rule.check(CLASSES);
        }
    }

    private String[] otherModulesAdapterPackages(String exclude) {
        return java.util.Arrays.stream(MODULES)
            .filter(m -> !m.equals(exclude))
            .map(m -> BASE_PACKAGE + "." + m + ".adapter..")
            .toArray(String[]::new);
    }
}
