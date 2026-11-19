package com.cashi.fee

import com.cashi.fee.api.FeeApiExamples
import com.cashi.fee.domain.DateFormatter
import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.Money
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionFactoryImpl
import com.cashi.fee.workflow.FeeCharge
import com.cashi.fee.workflow.FeeWorkflow
import com.cashi.fee.workflow.RestateFeeWorkflowGateway
import com.cashi.fee.workflow.model.BigDecimalAsString
import com.cashi.fee.workflow.model.LocalDateTimeAsString
import com.cashi.fee.workflow.model.RejectionMetadata
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.JavaType
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import dev.restate.client.Client
import dev.restate.client.kotlin.attachSuspend
import dev.restate.client.kotlin.workflowHandle
import dev.restate.sdk.common.TerminalException
import dev.restate.sdk.kotlin.runBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Architecture rules on the production classes:
 * - each package uses only the packages it may (`api → service → domain`,
 *   `workflow → service, domain`, `persistence → domain`), and the domain uses only the JDK and
 *   the Kotlin stdlib;
 * - no `Double` or `Float` on the money path, so no amount loses a digit;
 * - the workflow stays replay-safe: no wall clock or random id, no terminal failure after the
 *   charge, database calls only inside a step, one writer of `fee_record`;
 * - only the gateway talks to the Restate client, and nothing waits by attaching;
 * - business logic lives in a `…Service` interface and its `…ServiceImpl` (or a `…Factory` and its
 *   `…FactoryImpl`), not in a top-level `object`;
 * - only `domain` builds a `Transaction`, so none skips its checks.
 *
 * Each rule is also run against a planted offender, so a green run means the rule really bites.
 */
class ArchitectureTest {

    private val classes: JavaClasses =
        ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.cashi.fee")

    @Test
    fun `the production classes were imported`() {
        listOf("domain", "service", "workflow", "persistence", "api", "config").forEach { layer ->
            assertTrue(classes.any { it.packageName == "com.cashi.fee.$layer" }) { "no classes found in $layer" }
        }
        assertTrue(classes.none { it.simpleName.endsWith("Test") || it.simpleName.endsWith("IT") }) { "test classes were imported" }
    }

    @Test
    fun `each package uses only the packages the plan allows`() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(DOMAIN).definedBy("com.cashi.fee.domain..")
            .layer(SERVICE).definedBy("com.cashi.fee.service..")
            .layer(WORKFLOW).definedBy("com.cashi.fee.workflow..")
            .layer(PERSISTENCE).definedBy("com.cashi.fee.persistence..")
            .layer(API).definedBy("com.cashi.fee.api..")
            .layer(CONFIG).definedBy("com.cashi.fee.config..")
            .layer(ROOT).definedBy("com.cashi.fee")
            .whereLayer(DOMAIN).mayNotAccessAnyLayer()
            .whereLayer(SERVICE).mayOnlyAccessLayers(DOMAIN)
            .whereLayer(WORKFLOW).mayOnlyAccessLayers(SERVICE, DOMAIN)
            .whereLayer(PERSISTENCE).mayOnlyAccessLayers(DOMAIN)
            .whereLayer(API).mayOnlyAccessLayers(SERVICE, DOMAIN)
            .whereLayer(CONFIG).mayOnlyAccessLayers(DOMAIN)
            .whereLayer(ROOT).mayNotAccessAnyLayer()
            .check(classes)
    }

    @Test
    fun `the domain depends on the JDK and the Kotlin stdlib only`() {
        classes()
            .that().resideInAPackage("com.cashi.fee.domain..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("com.cashi.fee.domain..", "java..", "kotlin..", "org.jetbrains.annotations..")
            .because("the domain holds the fee rules and must not know Spring, Jakarta, Restate or kotlinx")
            .check(classes)
    }

    @Test
    fun `no field in the five packages is a double or a float`() {
        FloatingPoint.noFields(*MONEY_PACKAGES).check(classes)
    }

    @Test
    fun `no method in the five packages returns a double or a float`() {
        FloatingPoint.noReturnTypes(*MONEY_PACKAGES).check(classes)
    }

    @Test
    fun `no method or constructor in the five packages takes a double or a float`() {
        FloatingPoint.noParameters(*MONEY_PACKAGES).check(classes)
    }

    @Test
    fun `nothing in the five packages converts money to or from a double or a float`() {
        FloatingPoint.noConversions(*MONEY_PACKAGES).check(classes)
    }

    /** Each floating-point rule fails on a class that breaks it, so a green run above means something. */
    @Test
    fun `the floating-point rules catch what they claim to`() {
        listOf(
            FloatingPoint.noFields("com.cashi.fee..") to DoubleField::class.java,
            FloatingPoint.noReturnTypes("com.cashi.fee..") to FloatReturn::class.java,
            FloatingPoint.noReturnTypes("com.cashi.fee..") to DoubleTypeArgument::class.java,
            FloatingPoint.noParameters("com.cashi.fee..") to DoubleParameter::class.java,
            FloatingPoint.noConversions("com.cashi.fee..") to BigDecimalOfDouble::class.java,
            FloatingPoint.noConversions("com.cashi.fee..") to BigDecimalValueOfDouble::class.java,
            FloatingPoint.noConversions("com.cashi.fee..") to NumberToDouble::class.java,
            FloatingPoint.noConversions("com.cashi.fee..") to StringToDouble::class.java,
        ).forEach { (rule, offender) ->
            val failure = assertThrows<AssertionError>("${offender.simpleName} passed: ${rule.description}") {
                rule.check(ClassFileImporter().importClasses(offender))
            }
            // A violation, not ArchUnit's "failed to check any classes".
            assertTrue(failure.message.orEmpty().contains("was violated")) { "${offender.simpleName}: ${failure.message}" }
        }
    }

    @Test
    fun `the workflow reads no wall clock and mints no random id`() {
        Durability.noClockOrRandom(WORKFLOW_PACKAGE).check(classes)
    }

    @Test
    fun `only the pre-charge rejection and FeeCharge's fingerprint check build a TerminalException`() {
        Durability.terminalOnlyBeforeTheCharge(APP_PACKAGE).check(classes)
    }

    @Test
    fun `no method of FeeWorkflow or FeeCharge calls the fee_record repository`() {
        Durability.noDirectRepositoryCalls(FeeWorkflow::class.java, FeeCharge::class.java).check(classes)
    }

    @Test
    fun `in the workflow package, only lambdas inside a FeeWorkflow step call the fee_record repository`() {
        Durability.repositoryOnlyInSteps(FeeWorkflow::class.java, WORKFLOW_PACKAGE).check(classes)
    }

    /**
     * Proves the step rule above checks real call sites. `runBlock { withContext(IO) { … } }`
     * compiles to suspend-lambda classes nested in FeeWorkflow, and those make the calls.
     */
    @Test
    fun `FeeWorkflow calls find and save, each from a lambda inside a step`() {
        val calls = classes.filter { it.packageName.startsWith("com.cashi.fee.workflow") }
            .flatMap { it.methodCallsFromSelf }
            .filter { Durability.REPOSITORY_CALL.test(it) }

        assertEquals(setOf("find", "save"), calls.map { it.name }.toSet(), calls.joinToString { it.description })
        calls.forEach { call ->
            assertTrue(Durability.stepLambdaOf(FeeWorkflow::class.java).test(call.originOwner)) { "${call.originOwner.name} is not inside a step" }
        }
    }

    @Test
    fun `fee_record has one writer - nothing outside workflow and persistence calls save`() {
        Durability.oneWriter().check(classes)
    }

    @Test
    fun `only the gateway, with its nested classes, uses the Restate client`() {
        Durability.restateClientOnlyInTheGateway().check(classes)
    }

    @Test
    fun `api and service never attach to a run`() {
        Durability.noAttach(API_PACKAGE, SERVICE_PACKAGE).check(classes)
    }

    @Test
    fun `nothing calls a Restate attach - the gateway polls`() {
        Durability.noRestateAttach(APP_PACKAGE).check(classes)
    }

    /** Each durability rule fails on a class that breaks it, so a green run above means something. */
    @Test
    fun `the durability rules catch what they claim to`() {
        listOf(
            Durability.noClockOrRandom(APP_PACKAGE) to importClass(RandomUuid::class.java),
            Durability.noClockOrRandom(APP_PACKAGE) to importClass(InstantNow::class.java),
            Durability.noClockOrRandom(APP_PACKAGE) to importClass(LocalDateTimeNow::class.java),
            Durability.noClockOrRandom(APP_PACKAGE) to importClass(CurrentTimeMillis::class.java),
            Durability.noClockOrRandom(APP_PACKAGE) to importClass(KotlinClockSystem::class.java),
            Durability.terminalOnlyBeforeTheCharge(APP_PACKAGE) to importClass(TerminalAfterTheCharge::class.java),
            Durability.noDirectRepositoryCalls(DirectRepositoryCall::class.java) to importClass(DirectRepositoryCall::class.java),
            Durability.repositoryOnlyInSteps(LookupOutsideAStep::class.java, APP_PACKAGE) to importWithNested(LookupOutsideAStep::class.java),
            Durability.repositoryOnlyInSteps(DirectRepositoryCall::class.java, APP_PACKAGE) to importClass(DirectRepositoryCall::class.java),
            Durability.oneWriter() to importClass(SecondWriter::class.java),
            Durability.restateClientOnlyInTheGateway() to importClass(RestateClientOutsideTheGateway::class.java),
            Durability.noAttach(APP_PACKAGE) to importClass(AttachToAnything::class.java),
            Durability.noRestateAttach(APP_PACKAGE) to importClass(AttachToARun::class.java),
        ).forEach { (rule, offender) ->
            val names = offender.map { it.simpleName }.filter(String::isNotEmpty)
            val failure = assertThrows<AssertionError>("$names passed: ${rule.description}") { rule.check(offender) }
            // A violation, not ArchUnit's "failed to check any classes".
            assertTrue(failure.message.orEmpty().contains("was violated")) { "$names: ${failure.message}" }
        }
    }

    @Test
    fun `no top-level object in the six packages holds a public function, except the allow-list`() {
        Shape.noLogicInObjects(*SHAPE_PACKAGES).check(classes)
    }

    @Test
    fun `every class named Service is an interface`() {
        Shape.servicesAreInterfaces(APP_PACKAGE).check(classes)
    }

    @Test
    fun `every ServiceImpl implements exactly one Service, and every FactoryImpl one Factory, the one with its own stem`() {
        Shape.implsImplementTheirInterface(APP_PACKAGE).check(classes)
    }

    @Test
    fun `only the domain builds a Transaction`() {
        Shape.transactionsBuiltOnlyInTheDomain(APP_PACKAGE).check(classes)
    }

    /** Proves the rule above checks a real call site: the factory builds every Transaction. */
    @Test
    fun `the transaction factory is what builds a Transaction`() {
        val builders = classes.filter { type -> type.codeUnits.any { unit -> unit.callsFromSelf.any { Shape.TRANSACTION_BUILT.test(it) } } }
            .filterNot { it.name == Transaction::class.java.name } // its own copy calls its constructor
            .map { it.name }

        assertEquals(listOf(TransactionFactoryImpl::class.java.name), builders)
    }

    /** Keeps the allow-list free of stale names, and proves the object detection works. */
    @Test
    fun `every allow-listed object is a top-level object in production`() {
        val objects = classes.filter { Shape.isTopLevelObject(it) }.map { it.name }.toSet()

        assertTrue(objects.containsAll(Shape.ALLOWED_OBJECTS)) { "allow-listed: ${Shape.ALLOWED_OBJECTS}; top-level objects found: $objects" }
    }

    /** Each shape rule fails on a class that breaks it, so a green run above means something. */
    @Test
    fun `the shape rules catch what they claim to`() {
        listOf(
            Shape.noLogicInObjects(APP_PACKAGE) to importClass(LogicInAnObject::class.java),
            // A real production object, taken off the allow-list, must fail the rule too.
            Shape.noLogicInObjects(APP_PACKAGE, allowed = emptySet()) to importClass(DateFormatter::class.java),
            Shape.servicesAreInterfaces(APP_PACKAGE) to importClass(PricingClassService::class.java),
            Shape.servicesAreInterfaces(APP_PACKAGE) to importClass(AbstractPricingService::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(UnboundServiceImpl::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(MismatchedServiceImpl::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(DoubleDutyServiceImpl::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(UnboundFactoryImpl::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(MismatchedFactoryImpl::class.java),
            Shape.implsImplementTheirInterface(APP_PACKAGE) to importClass(DoubleDutyFactoryImpl::class.java),
            Shape.transactionsBuiltOnlyInTheDomain(APP_PACKAGE) to importClass(TransactionBuiltOutsideTheDomain::class.java),
            Shape.transactionsBuiltOnlyInTheDomain(APP_PACKAGE) to importClass(TransactionCopiedOutsideTheDomain::class.java),
        ).forEach { (rule, offender) ->
            val names = offender.map { it.simpleName }.filter(String::isNotEmpty)
            val failure = assertThrows<AssertionError>("$names passed: ${rule.description}") { rule.check(offender) }
            // A violation, not ArchUnit's "failed to check any classes".
            assertTrue(failure.message.orEmpty().contains("was violated")) { "$names: ${failure.message}" }
        }
    }

    /** The object rule is not a ban on objects: these allowed shapes pass. */
    @Test
    fun `the shape rules let a companion object, an allow-listed object and a matching pair through`() {
        assertDoesNotThrow { Shape.noLogicInObjects(APP_PACKAGE).check(importWithNested(FactoryOnACompanion::class.java)) }
        assertDoesNotThrow { Shape.noLogicInObjects(APP_PACKAGE).check(importClass(DateFormatter::class.java)) }
        assertDoesNotThrow { Shape.implsImplementTheirInterface(APP_PACKAGE).check(importClass(PricingServiceImpl::class.java)) }
        assertDoesNotThrow { Shape.implsImplementTheirInterface(APP_PACKAGE).check(importClass(QuoteFactoryImpl::class.java)) }
    }

    /** `Money` is a value class with its helpers on its companion, so it needs no allow-list entry. */
    @Test
    fun `Money is no top-level object, and passes the object rule with no allow-list`() {
        val money = importWithNested(Money::class.java)

        assertTrue(money.none { Shape.isTopLevelObject(it) }) { money.joinToString { it.name } }
        assertDoesNotThrow { Shape.noLogicInObjects(APP_PACKAGE, allowed = emptySet()).check(money) }
    }

    /** The step rule is no blanket ban: a repository call inside a step block passes it, in any class. */
    @Test
    fun `the step rule lets a call inside a step block through`() {
        assertDoesNotThrow { Durability.repositoryOnlyInSteps(LookupInAStep::class.java, APP_PACKAGE).check(importWithNested(LookupInAStep::class.java)) }
    }

    /**
     * The durability rules. Each takes the packages or classes it guards, so the offender classes
     * below can be checked against the same rule.
     */
    private object Durability {

        /** A replay re-runs the code between steps, so that code must compute the same values. */
        fun noClockOrRandom(vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages)
                .should().accessTargetWhere(NON_DETERMINISTIC)
                .because("a replay must see what the first attempt saw: time comes from Clock.Restate and ids from random() (AC22, R8)")

        /**
         * A terminal failure after the charge would end the run without its record. Only the
         * pre-charge rejection and FeeCharge's fingerprint check, which changes nothing, may build
         * one. A subclass's constructor counts too.
         */
        fun terminalOnlyBeforeTheCharge(vararg packages: String): ArchRule =
            codeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(*packages)
                .and(not(DescribedPredicate.describe("FeeWorkflow.rejectBeforeCharge or FeeCharge.refuseOtherPayload") { unit: JavaCodeUnit ->
                    (unit.owner.name to unit.name) in TERMINAL_ALLOWED
                }))
                .should(notConstruct(TerminalException::class.java))
                .because("nothing after the charge may fail terminally (D2b, AC23, R3)")

        /** A repository call in a handler's own method would run outside any step, on every replay. */
        fun noDirectRepositoryCalls(vararg handlers: Class<*>): ArchRule =
            noClasses().that(DescribedPredicate.describe("are ${handlers.joinToString { it.simpleName }}") { type: JavaClass ->
                type.name in handlers.map { it.name }
            })
                .should().callMethodWhere(REPOSITORY_CALL)
                .because("Postgres is reached only inside a durable step, whose result the journal keeps (R8)")

        /**
         * In [packages], a repository call sits only in a lambda inside a step of [workflow].
         * A `withContext(IO) { … }` outside a `runBlock` is a lambda with a receiver, so it fails.
         */
        fun repositoryOnlyInSteps(workflow: Class<*>, vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages).and(not(stepLambdaOf(workflow)))
                .should().callMethodWhere(REPOSITORY_CALL)
                .because("Postgres is reached only inside a durable step, whose result the journal keeps (R8)")

        /** The `record` step is the one writer of `fee_record`. */
        fun oneWriter(): ArchRule =
            noClasses().that().resideInAPackage(APP_PACKAGE)
                .and().resideOutsideOfPackages(WORKFLOW_PACKAGE, PERSISTENCE_PACKAGE)
                .should().callMethodWhere(REPOSITORY_SAVE)
                .because("fee_record has one writer, the workflow's record step (A3)")

        /** The gateway is the one class that knows the Restate client. */
        fun restateClientOnlyInTheGateway(): ArchRule =
            noClasses().that().resideInAPackage(APP_PACKAGE)
                .and().doNotBelongToAnyOf(RestateFeeWorkflowGateway::class.java)
                .should().dependOnClassesThat().resideInAPackage("dev.restate.client..")
                .because("RestateFeeWorkflowGateway is the only class that touches the Restate client (plan §2)")

        /** The API waits by polling, never by attaching, so nothing is held open. */
        fun noAttach(vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages)
                .should().callMethodWhere(DescribedPredicate.describe("an attach* method") { call: JavaMethodCall -> call.name.startsWith("attach") })
                .because("the API waits by polling the output with a deadline, never by attaching (plan §3)")

        fun noRestateAttach(vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages)
                .should().callMethodWhere(DescribedPredicate.describe("a Restate attach* method") { call: JavaMethodCall ->
                    call.targetOwner.packageName.startsWith("dev.restate") && call.name.startsWith("attach")
                })
                .because("the gateway polls the output with a deadline, never attaches (plan §3)")

        /**
         * A suspend lambda in [workflow] whose outermost lambda is a `suspend () -> T` block, the
         * shape `runBlock` takes. ArchUnit can't see the inlined `runBlock` call, so the shape
         * stands for it. A lambda with a receiver (`withContext`) is not a step.
         */
        fun stepLambdaOf(workflow: Class<*>): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe("are lambdas inside a ${workflow.simpleName} step") { type: JavaClass ->
                val chain = generateSequence(type) { it.enclosingClass.orElse(null) }.toList()
                val workflowAt = chain.indexOfFirst { it.name == workflow.name }
                if (workflowAt < 1) return@describe false
                val lambdas = chain.subList(0, workflowAt)
                lambdas.all { it.isSuspendLambda() } && lambdas.last().rawInterfaces.any { it.name == STEP_BLOCK }
            }

        val REPOSITORY_CALL: DescribedPredicate<JavaMethodCall> =
            DescribedPredicate.describe("a FeeRecordRepository method") { call -> call.targetOwner.isAssignableTo(FeeRecordRepository::class.java) }

        private val REPOSITORY_SAVE: DescribedPredicate<JavaMethodCall> =
            DescribedPredicate.describe("FeeRecordRepository.save") { call -> REPOSITORY_CALL.test(call) && call.name == "save" }

        /** Matched by name, because `kotlinx.datetime` isn't on the classpath. */
        private val NON_DETERMINISTIC: DescribedPredicate<JavaAccess<*>> =
            DescribedPredicate.describe(
                "UUID.randomUUID, Instant.now, LocalDateTime.now, System.currentTimeMillis, or kotlin.time / kotlinx.datetime Clock.System",
            ) { access ->
                val name = access.target.name
                when (access.targetOwner.name) {
                    "java.util.UUID" -> name == "randomUUID"
                    "java.time.Instant", "java.time.LocalDateTime" -> name == "now"
                    "java.lang.System" -> name == "currentTimeMillis"
                    "kotlin.time.Clock\$System", "kotlinx.datetime.Clock\$System" -> true
                    else -> false
                }
            }

        private val TERMINAL_ALLOWED: Set<Pair<String, String>> = setOf(
            FeeWorkflow::class.java.name to "rejectBeforeCharge",
            FeeCharge::class.java.name to "refuseOtherPayload",
        )

        /** `suspend () -> T` compiles to a `Function1` that takes only its continuation. */
        private const val STEP_BLOCK = "kotlin.jvm.functions.Function1"

        private fun JavaClass.isSuspendLambda(): Boolean =
            rawSuperclass.map { it.name == "kotlin.coroutines.jvm.internal.SuspendLambda" }.orElse(false)

        private fun notConstruct(type: Class<*>): ArchCondition<JavaCodeUnit> =
            object : ArchCondition<JavaCodeUnit>("not construct a ${type.simpleName}") {
                override fun check(unit: JavaCodeUnit, events: ConditionEvents) {
                    unit.constructorCallsFromSelf.filter { it.targetOwner.isAssignableTo(type) }.forEach { call ->
                        events.add(SimpleConditionEvent.violated(unit, "${unit.fullName} constructs ${call.targetOwner.name} in ${call.sourceCodeLocation}"))
                    }
                }
            }
    }

    /**
     * The shape rules. Each takes the packages it guards, so the offender classes below can be
     * checked against the same rule.
     */
    private object Shape {

        /** The top-level objects that may hold public functions: a value helper, codecs, constants. */
        val ALLOWED_OBJECTS: Set<String> = setOf(
            DateFormatter::class.java.name,
            RejectionMetadata::class.java.name,
            BigDecimalAsString::class.java.name,
            LocalDateTimeAsString::class.java.name,
            FeeApiExamples::class.java.name,
        )

        /**
         * A top-level `object` holds no public function unless [allowed]. Every class of
         * [packages] is checked, so the rule never runs empty.
         */
        fun noLogicInObjects(vararg packages: String, allowed: Set<String> = ALLOWED_OBJECTS): ArchRule =
            classes().that().resideInAnyPackage(*packages)
                .should(notBeATopLevelObjectWithPublicFunctions(allowed))
                .because("business logic lives in a …Service and its …ServiceImpl; only value helpers, codecs and constants stay objects (Q14, Q15, Q17)")

        /** A `…Service` is the interface its callers take and its tests fake. */
        fun servicesAreInterfaces(vararg packages: String): ArchRule =
            classes().that().resideInAnyPackage(*packages).and().haveSimpleNameEndingWith(SERVICE_SUFFIX)
                .should().beInterfaces()
                .because("a …Service is an interface; its implementation is the …ServiceImpl (Q14)")

        /** A `…ServiceImpl` implements its own `…Service` and no other; the same for factories. */
        fun implsImplementTheirInterface(vararg packages: String): ArchRule =
            classes().that().resideInAnyPackage(*packages).and(haveAnImplName())
                .should(implementExactlyTheInterfaceOfTheirStem())
                .because("each …ServiceImpl implements the one …Service with its own stem (Q14), each …FactoryImpl the one …Factory (Q19)")

        /**
         * Only `domain` builds a [Transaction], so no other layer holds one that skipped the checks.
         * `internal` can't enforce it, because all of `src/main` is one module. `copy` counts too.
         */
        fun transactionsBuiltOnlyInTheDomain(vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages).and().resideOutsideOfPackage(DOMAIN_PACKAGE)
                .should().callCodeUnitWhere(TRANSACTION_BUILT)
                .because("only the domain builds a Transaction, through TransactionFactory, so none skips its checks (Q19)")

        /** A call of [Transaction]'s constructor or `copy` (`internal` mangles it to `copy$<module>`). */
        val TRANSACTION_BUILT: DescribedPredicate<JavaCall<*>> =
            DescribedPredicate.describe("Transaction's constructor or its copy") { call ->
                call.targetOwner.name == Transaction::class.java.name && (call.name == CONSTRUCTOR || call.name.startsWith("copy"))
            }

        /**
         * A Kotlin `object` declared at the top level of a file: it compiles to a class with a static
         * `INSTANCE` field of its own type. A companion object is a nested class (`…$Companion`), and
         * a class's file facade (`…Kt`) has no `INSTANCE`, so neither is one.
         */
        fun isTopLevelObject(type: JavaClass): Boolean =
            type.isTopLevelClass &&
                type.tryGetField("INSTANCE").map { JavaModifier.STATIC in it.modifiers && it.rawType.name == type.name }.orElse(false)

        /** A function a caller can call: public, and written in the source, not a compiler bridge. */
        private fun JavaMethod.isPublicFunction(): Boolean =
            JavaModifier.PUBLIC in modifiers && JavaModifier.SYNTHETIC !in modifiers && JavaModifier.BRIDGE !in modifiers

        private fun notBeATopLevelObjectWithPublicFunctions(allowed: Set<String>): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>("not be a top-level object with a public function, unless allow-listed") {
                override fun check(type: JavaClass, events: ConditionEvents) {
                    if (!isTopLevelObject(type) || type.name in allowed) return
                    val functions = type.methods.filter { it.isPublicFunction() }.map { it.name }.sorted()
                    if (functions.isNotEmpty()) {
                        events.add(SimpleConditionEvent.violated(type, "${type.name} is a top-level object with public functions $functions in ${type.sourceCodeLocation}"))
                    }
                }
            }

        /** `Service` or `Factory` for a class named `…<Role>Impl`; null for any other name. */
        private fun roleOf(type: JavaClass): String? = ROLES.firstOrNull { type.simpleName.endsWith(it + IMPL) }

        private fun haveAnImplName(): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe("have a simple name ending with ${ROLES.joinToString(" or ") { it + IMPL }}") { type -> roleOf(type) != null }

        private fun implementExactlyTheInterfaceOfTheirStem(): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>("implement exactly one …Service or …Factory, the one with their stem") {
                override fun check(type: JavaClass, events: ConditionEvents) {
                    val role = roleOf(type) ?: return
                    val expected = type.simpleName.removeSuffix(role + IMPL) + role
                    val implemented = type.rawInterfaces.filter { it.simpleName.endsWith(role) }
                    if (implemented.map { it.simpleName } != listOf(expected)) {
                        events.add(
                            SimpleConditionEvent.violated(
                                type,
                                "${type.name} implements the ${role.lowercase()} interfaces ${implemented.map { it.name }}, not exactly $expected, in ${type.sourceCodeLocation}",
                            ),
                        )
                    }
                }
            }

        /** The roles whose implementation is named `…<Role>Impl` after its interface `…<Role>`. */
        private val ROLES = listOf("Service", "Factory")
        private const val SERVICE_SUFFIX = "Service"
        private const val IMPL = "Impl"
        private const val CONSTRUCTOR = "<init>"
    }

    /**
     * The no-floating-point rules. ArchUnit sees `Double` as `double` and `Double?` as
     * `java.lang.Double`, so both are named. `toDouble()` compiles to `doubleValue()` or
     * `parseDouble`. On an `Int` it makes no call, but the type rules or the
     * `BigDecimal(double)` ban catch its result.
     */
    private object FloatingPoint {
        val TYPES: Set<String> = setOf("double", "float", "java.lang.Double", "java.lang.Float")

        fun noFields(vararg packages: String): ArchRule =
            fields().that().areDeclaredInClassesThat().resideInAnyPackage(*packages)
                .should(notInvolve("have a double or float type") { field: JavaField -> listOf(field.type) })
                .because(BECAUSE)

        fun noReturnTypes(vararg packages: String): ArchRule =
            codeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(*packages)
                .should(notInvolve("return a double or a float") { unit: JavaCodeUnit -> listOf(unit.returnType) })
                .because(BECAUSE)

        fun noParameters(vararg packages: String): ArchRule =
            codeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(*packages)
                .should(notInvolve("take a double or float parameter") { unit: JavaCodeUnit -> unit.parameterTypes })
                .because(BECAUSE)

        fun noConversions(vararg packages: String): ArchRule =
            noClasses().that().resideInAnyPackage(*packages)
                .should().callCodeUnitWhere(CONVERSION)
                .because(BECAUSE)

        private const val BECAUSE = "money is BigDecimal from the HTTP body to the database and back (AC8)"

        private val CONVERSION: DescribedPredicate<JavaCall<*>> =
            DescribedPredicate.describe("BigDecimal(double), BigDecimal.valueOf(double) or a toDouble()/toFloat() conversion") { call ->
                val target = call.target
                val owner = target.owner.name
                val name = target.name
                when {
                    owner == "java.math.BigDecimal" && name in setOf("<init>", "valueOf") ->
                        target.rawParameterTypes.firstOrNull()?.name in TYPES
                    owner in setOf("java.lang.Double", "java.lang.Float") -> name in setOf("parseDouble", "parseFloat")
                    else -> name in setOf("doubleValue", "floatValue") || name.startsWith("toDouble") || name.startsWith("toFloat")
                }
            }

        private fun <T : JavaMember> notInvolve(description: String, types: (T) -> List<JavaType>): ArchCondition<T> =
            object : ArchCondition<T>("not $description") {
                override fun check(member: T, events: ConditionEvents) {
                    val found = types(member).flatMap { it.allInvolvedRawTypes }.map { it.name }.filter { it in TYPES }
                    if (found.isNotEmpty()) {
                        events.add(SimpleConditionEvent.violated(member, "${member.fullName} uses ${found.distinct()} in ${member.sourceCodeLocation}"))
                    }
                }
            }
    }

    // Offenders for the floating-point rules, one rule broken each. Test classes, so never imported above.
    @Suppress("unused")
    private class DoubleField {
        private val rate = 0.0015
    }

    @Suppress("unused")
    private class FloatReturn {
        fun rate(): Float = 0.0015f
    }

    @Suppress("unused")
    private class DoubleTypeArgument {
        fun rates(): List<Double?> = emptyList()
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class DoubleParameter {
        fun scale(factor: Double?) = Unit
    }

    @Suppress("unused")
    private class BigDecimalOfDouble {
        fun fee(): BigDecimal = BigDecimal(0.1)
    }

    @Suppress("unused")
    private class BigDecimalValueOfDouble {
        fun fee(): BigDecimal = BigDecimal.valueOf(0.1)
    }

    @Suppress("unused")
    private class NumberToDouble {
        fun fee(amount: BigDecimal): String = amount.toDouble().toString()
    }

    @Suppress("unused")
    private class StringToDouble {
        fun fee(amount: String): String = amount.toDouble().toString()
    }

    // Offenders for the durability rules, one rule broken each. Test classes, so never imported above.
    @Suppress("unused")
    private class RandomUuid {
        fun chargeId(): String = UUID.randomUUID().toString()
    }

    @Suppress("unused")
    private class InstantNow {
        fun chargedAt(): Instant = Instant.now()
    }

    @Suppress("unused")
    private class LocalDateTimeNow {
        fun recordedAt(): LocalDateTime = LocalDateTime.now()
    }

    @Suppress("unused")
    private class CurrentTimeMillis {
        fun stamp(): Long = System.currentTimeMillis()
    }

    @OptIn(ExperimentalTime::class)
    @Suppress("unused")
    private class KotlinClockSystem {
        fun chargedAt(): String = Clock.System.now().toString()
    }

    @Suppress("unused")
    private class TerminalAfterTheCharge {
        fun recordFailed(): Nothing = throw TerminalException(500, "record failed")
    }

    @Suppress("unused")
    private class DirectRepositoryCall(private val records: FeeRecordRepository) {
        suspend fun run(key: String): FeeRecord? = records.find(key)
    }

    /** `withContext` with no `runBlock` around it: every replay redoes the read, maybe differently. */
    @Suppress("unused")
    private class LookupOutsideAStep(private val records: FeeRecordRepository) {
        suspend fun run(key: String): FeeRecord? = withContext(Dispatchers.IO) { records.find(key) }
    }

    /** The allowed shape, FeeWorkflow's: the read inside a step. Compiled only, never run. */
    @Suppress("unused")
    private class LookupInAStep(private val records: FeeRecordRepository) {
        suspend fun run(key: String): String = runBlock("lookup") { withContext(Dispatchers.IO) { records.find(key)?.transactionId ?: "" } }
    }

    @Suppress("unused")
    private class SecondWriter(private val records: FeeRecordRepository) {
        fun overwrite(record: FeeRecord) = records.save(record)
    }

    @Suppress("unused")
    private class RestateClientOutsideTheGateway(private val client: Client) {
        fun connected(): Boolean = client.toString().isNotEmpty()
    }

    private interface RunHandle {
        fun attach(): String
    }

    @Suppress("unused")
    private class AttachToAnything(private val run: RunHandle) {
        fun output(): String = run.attach()
    }

    @Suppress("unused")
    private class AttachToARun(private val client: Client) {
        suspend fun output(id: String): String = client.workflowHandle<String>("FeeWorkflow", id).attachSuspend().response()
    }

    // Offenders for the shape rules, one rule broken each, and the allowed shape. Test classes, so
    // never imported above. The object offender sits after this class, at the top level of the file.
    private interface PricingService {
        fun price(amount: BigDecimal): BigDecimal
    }

    private interface DoubleDutyService

    @Suppress("unused")
    private class PricingClassService {
        fun price(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private abstract class AbstractPricingService {
        abstract fun price(amount: BigDecimal): BigDecimal
    }

    @Suppress("unused")
    private class UnboundServiceImpl {
        fun price(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private class MismatchedServiceImpl : PricingService {
        override fun price(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private class DoubleDutyServiceImpl : DoubleDutyService, PricingService {
        override fun price(amount: BigDecimal): BigDecimal = amount
    }

    /** The allowed shape: the implementation of its own stem's service. */
    @Suppress("unused")
    private class PricingServiceImpl : PricingService {
        override fun price(amount: BigDecimal): BigDecimal = amount
    }

    // The same for factories, and the allowed shape.
    private interface QuoteFactory {
        fun create(amount: BigDecimal): BigDecimal
    }

    private interface DoubleDutyFactory

    @Suppress("unused")
    private class UnboundFactoryImpl {
        fun create(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private class MismatchedFactoryImpl : QuoteFactory {
        override fun create(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private class DoubleDutyFactoryImpl : DoubleDutyFactory, QuoteFactory {
        override fun create(amount: BigDecimal): BigDecimal = amount
    }

    @Suppress("unused")
    private class QuoteFactoryImpl : QuoteFactory {
        override fun create(amount: BigDecimal): BigDecimal = amount
    }

    // Offenders for the Transaction rule, outside `domain`: one uses the constructor, one copy.
    // Tests share the module, so `internal` lets them.
    @Suppress("unused")
    private class TransactionBuiltOutsideTheDomain {
        fun forge(amount: Money): Transaction =
            Transaction("txn_forged", amount, amount.asset.type, "Mobile Top Up", Transaction.ELIGIBLE_STATE, LocalDateTime.of(2023, 8, 30, 15, 42, 17))
    }

    @Suppress("unused")
    private class TransactionCopiedOutsideTheDomain {
        fun ineligible(transaction: Transaction): Transaction = transaction.copy(state = "SETTLED")
    }

    /** The class alone: its violations are in its own methods. */
    private fun importClass(type: Class<*>): JavaClasses = ClassFileImporter().importClasses(type)

    /** The class and every class nested in it, such as the lambdas its methods compile to. */
    private fun importWithNested(type: Class<*>): JavaClasses {
        val path = type.name.replace('.', '/')
        return ClassFileImporter()
            .withImportOption { location -> location.contains("$path.class") || location.contains("$path\$") }
            .importPackages(type.packageName)
    }

    private companion object {
        /** The five application packages; the money path runs through all of them. */
        val MONEY_PACKAGES = arrayOf(
            "com.cashi.fee.domain..",
            "com.cashi.fee.service..",
            "com.cashi.fee.workflow..",
            "com.cashi.fee.persistence..",
            "com.cashi.fee.api..",
        )

        /** The six packages of the shape rule: the five above and `config`. */
        val SHAPE_PACKAGES = MONEY_PACKAGES + "com.cashi.fee.config.."

        const val APP_PACKAGE = "com.cashi.fee.."
        const val DOMAIN_PACKAGE = "com.cashi.fee.domain.."
        const val API_PACKAGE = "com.cashi.fee.api.."
        const val SERVICE_PACKAGE = "com.cashi.fee.service.."
        const val WORKFLOW_PACKAGE = "com.cashi.fee.workflow.."
        const val PERSISTENCE_PACKAGE = "com.cashi.fee.persistence.."

        const val DOMAIN = "domain"
        const val SERVICE = "service"
        const val WORKFLOW = "workflow"
        const val PERSISTENCE = "persistence"
        const val API = "api"
        const val CONFIG = "config"
        const val ROOT = "root"
    }
}

/** The object rule's offender: business logic in a top-level object. Never imported above. */
@Suppress("unused")
private object LogicInAnObject {
    fun feeOf(amount: BigDecimal): BigDecimal = amount.movePointLeft(3)
}

/** The shape the object rule allows: a factory on a companion object. */
@Suppress("unused")
private class FactoryOnACompanion private constructor(val code: String) {
    companion object {
        fun of(code: String): FactoryOnACompanion = FactoryOnACompanion(code)
    }
}
