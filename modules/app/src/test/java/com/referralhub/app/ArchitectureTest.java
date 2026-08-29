package com.referralhub.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * The module boundaries, enforced rather than described.
 *
 * <p>The README claims a dependency graph. Gradle enforces it at the module level, but nothing
 * stops a package inside one module from reaching into another's internals once both are on the
 * same classpath — which, in a modular monolith, they always are. These rules are what make the
 * claim true instead of aspirational, and they are why extracting a module later is a deployment
 * change rather than an archaeology project.
 *
 * <p>Runs in {@code app} because it is the only module whose classpath contains all six.
 */
@AnalyzeClasses(packages = "com.referralhub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // -----------------------------------------------------------------------------------
    // The dependency graph
    // -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule common_depends_on_no_feature_module = noClasses()
            .that().resideInAPackage("com.referralhub.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.referralhub.ingestion..", "com.referralhub.dedup..",
                    "com.referralhub.search..", "com.referralhub.referral..",
                    "com.referralhub.trust..", "com.referralhub.app..")
            .because("common is the foundation; a dependency from it to a feature module makes "
                    + "the graph cyclic and every module transitively depend on every other");

    @ArchTest
    static final ArchRule ingestion_knows_only_common = noClasses()
            .that().resideInAPackage("com.referralhub.ingestion..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.referralhub.dedup..", "com.referralhub.search..",
                    "com.referralhub.referral..", "com.referralhub.trust..")
            .because("ingestion publishes events and knows nothing about who consumes them");

    @ArchTest
    static final ArchRule dedup_does_not_know_about_search_or_referrals = noClasses()
            .that().resideInAPackage("com.referralhub.dedup..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.referralhub.search..", "com.referralhub.referral..",
                    "com.referralhub.trust..")
            .because("dedup produces canonical jobs; what indexes or refers them is not its concern");

    @ArchTest
    static final ArchRule search_does_not_know_about_referrals = noClasses()
            .that().resideInAPackage("com.referralhub.search..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.referralhub.referral..", "com.referralhub.trust..")
            .because("search is a read model over canonical jobs and nothing more");

    @ArchTest
    static final ArchRule trust_does_not_know_about_referrals = noClasses()
            .that().resideInAPackage("com.referralhub.trust..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.referralhub.referral..", "com.referralhub.dedup..",
                    "com.referralhub.search..")
            .because("trust answers 'is this person a verified employee' without knowing why");

    @ArchTest
    static final ArchRule no_feature_module_depends_on_the_application = noClasses()
            .that().resideInAnyPackage("com.referralhub.common..", "com.referralhub.ingestion..",
                    "com.referralhub.dedup..", "com.referralhub.search..",
                    "com.referralhub.referral..", "com.referralhub.trust..")
            .should().dependOnClassesThat().resideInAPackage("com.referralhub.app..")
            .because("the application assembles the modules; the modules must not assemble it");

    @ArchTest
    static final ArchRule no_cycles_between_modules = slices()
            .matching("com.referralhub.(*)..")
            .should().beFreeOfCycles();

    // -----------------------------------------------------------------------------------
    // Layering inside a module
    // -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule controllers_live_in_api_packages = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..api..")
            .orShould().resideInAPackage("com.referralhub.common.error..")
            .because("a reader looking for the HTTP surface of a module should find all of it "
                    + "in one package");

    @ArchTest
    static final ArchRule stores_are_repositories = classes()
            .that().haveSimpleNameEndingWith("Store")
            .and().resideOutsideOfPackage("..testing..")
            .and().areNotRecords()
            .should().beAnnotatedWith(org.springframework.stereotype.Repository.class)
            .because("the naming convention should mean something enforceable");

    @ArchTest
    static final ArchRule controllers_are_not_called_from_services = classes()
            .that().resideInAPackage("..api..")
            .should().onlyHaveDependentClassesThat()
            .resideInAnyPackage("..api..", "com.referralhub.app..")
            .because("the HTTP layer is an entry point, never a collaborator");

    @ArchTest
    static final ArchRule properties_classes_are_configuration_properties = classes()
            .that().haveSimpleNameEndingWith("Properties")
            .and().resideOutsideOfPackage("..testing..")
            .and().areNotNestedClasses()
            .should().beAnnotatedWith(
                    org.springframework.boot.context.properties.ConfigurationProperties.class)
            .because("configuration should be bound and validated, not read ad hoc");

    // -----------------------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule domain_events_are_immutable_records = classes()
            .that().implement(com.referralhub.common.events.DomainEvent.class)
            .should().beRecords()
            .because("an event is a fact that already happened; a mutable one is a lie waiting "
                    + "to be told");

    @ArchTest
    static final ArchRule only_the_outbox_publishes_to_kafka = noClasses()
            .that().resideInAnyPackage("com.referralhub.ingestion..", "com.referralhub.dedup..",
                    "com.referralhub.search..", "com.referralhub.referral..",
                    "com.referralhub.trust..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
            .because("feature code emits through the transactional outbox; publishing directly "
                    + "reintroduces exactly the dual-write problem the outbox exists to remove");

    // -----------------------------------------------------------------------------------
    // General hygiene
    // -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_field_injection = fields()
            .should().notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
            .because("constructor injection makes a missing dependency a compile error and keeps "
                    + "classes constructible in a plain unit test");

    @ArchTest
    static final ArchRule no_standard_streams =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_joda_or_legacy_date_time =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

    @ArchTest
    static final ArchRule no_generic_exceptions =
            GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
