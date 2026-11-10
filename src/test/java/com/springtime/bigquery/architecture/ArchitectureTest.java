package com.springtime.bigquery.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Executable architecture rules for the modular monolith. */
@AnalyzeClasses(packages = "com.springtime.bigquery", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule messaging_is_independent_of_config_retail_and_control =
            noClasses().that().resideInAPackage("..messaging..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..retail..", "..config..", "..control..");

    @ArchTest
    static final ArchRule web_and_security_are_independent_of_retail_and_control =
            noClasses().that().resideInAnyPackage("..web..", "..security..")
                    .should().dependOnClassesThat().resideInAnyPackage("..retail..", "..control..");

    @ArchTest
    static final ArchRule api_does_not_reach_query_or_gateway_infrastructure =
            noClasses().that().resideInAnyPackage(
                            "..retail.api..", "..retail.event.api..", "..retail.inventory.api..",
                            "..retail.replenishment.api..", "..retail.purchase.api..",
                            "..control.supplier.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..retail.query..", "..retail.inventory.query..",
                            "..retail.inventory.sink..", "..gateway..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_application_or_infrastructure =
            noClasses().that().resideInAnyPackage(
                            "..retail.model..", "..retail.event.model..", "..retail.inventory.model..",
                            "..retail.replenishment.model..", "..retail.purchase.model..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..retail.api..", "..retail.service..", "..retail.event.application..",
                            "..retail.inventory.application..", "..retail.query..",
                            "..retail.inventory.query..", "..retail.inventory.sink..",
                            "..messaging..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_api =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.springtime.bigquery.api..", "..retail..api..", "..control..api..");

    @ArchTest
    static final ArchRule service_does_not_depend_on_api =
            noClasses().that().resideInAPackage("com.springtime.bigquery.service..")
                    .should().dependOnClassesThat().resideInAPackage("com.springtime.bigquery.api..");

    @ArchTest
    static final ArchRule control_does_not_depend_on_retail =
            noClasses().that().resideInAPackage("..control..")
                    .should().dependOnClassesThat().resideInAPackage("..retail..");

    @ArchTest
    static final ArchRule event_application_does_not_depend_on_sink_adapters =
            noClasses().that().resideInAPackage("..retail.event.application..")
                    .should().dependOnClassesThat().resideInAPackage("..retail.inventory.sink..");

    @ArchTest
    static final ArchRule purchase_does_not_depend_on_replenishment =
            noClasses().that().resideInAPackage("..retail.purchase..")
                    .should().dependOnClassesThat().resideInAPackage("..retail.replenishment..");

    @ArchTest
    static final ArchRule retail_reads_go_through_the_retail_executor =
            noClasses().that().resideInAPackage("..retail..")
                    .and().resideOutsideOfPackage("..retail.query..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.springtime.bigquery.model.query.QueryRequest");

    @ArchTest
    static final ArchRule purchase_application_does_not_depend_on_store =
            noClasses().that().resideInAPackage("..retail.purchase.application..")
                    .should().dependOnClassesThat().resideInAPackage("..retail.purchase.store..");

    @ArchTest
    static final ArchRule purchase_store_does_not_depend_on_api =
            noClasses().that().resideInAPackage("..retail.purchase.store..")
                    .should().dependOnClassesThat().resideInAPackage("..retail.purchase.api..");

    @ArchTest
    static final ArchRule replenishment_application_does_not_depend_on_store =
            noClasses().that().resideInAPackage("..retail.replenishment.application..")
                    .should().dependOnClassesThat().resideInAPackage("..retail.replenishment.store..");

    @ArchTest
    static final ArchRule retail_slices_are_cycle_free =
            slices().matching("com.springtime.bigquery.retail.(*)..")
                    .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule top_level_slices_are_cycle_free =
            slices().matching("com.springtime.bigquery.(*)..")
                    .should().beFreeOfCycles();
}
