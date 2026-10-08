package tanseki.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Architecture dependency rules for one module, as a real task type.
 *
 * A task type keeps the build configuration-cache friendly: every value the check
 * needs is a typed [Input] property populated once all projects have configured,
 * so the task action never references build-script objects. The old script form
 * captured free functions and Gradle objects in `doLast`, which the configuration
 * cache rejected.
 */
abstract class CheckDependencyRulesTask : DefaultTask() {
    @get:Input
    abstract val module: Property<String>

    /** Project paths this module is allowed to depend on. */
    @get:Input
    abstract val allowed: SetProperty<String>

    /** Configuration names that are test-only declarations. */
    @get:Input
    abstract val testConfigurations: SetProperty<String>

    /** `configuration name -> project-dependency targets` for this module. */
    @get:Input
    abstract val dependenciesByConfiguration: MapProperty<String, List<String>>

    @TaskAction
    fun check() {
        val moduleName = module.get()
        val allowedSet = allowed.get()
        val testSet = testConfigurations.get()
        val violations = mutableListOf<String>()
        dependenciesByConfiguration.get().forEach { (configuration, targets) ->
            val testConfig = configuration in testSet
            targets.forEach { target ->
                when {
                    target == ":service" || target == ":cli" ->
                        violations += "$moduleName ($configuration) -> $target: " +
                            "nothing may depend on service/cli"
                    target == ":testkit" ->
                        if (!testConfig && moduleName != ":testkit") {
                            violations += "$moduleName ($configuration) -> :testkit: " +
                                "testkit is test-only"
                        }
                    target !in allowedSet ->
                        violations += "$moduleName ($configuration) -> $target: " +
                            "not allowed by the architecture dependency rules"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Dependency-rule violations in $moduleName:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}