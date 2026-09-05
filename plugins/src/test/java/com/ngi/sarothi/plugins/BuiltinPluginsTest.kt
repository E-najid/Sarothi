package com.ngi.sarothi.plugins

import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.PluginCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract every plugin in the set has to hold, checked across all of them.
 *
 * These are the failures that would otherwise only show up as the model routing badly or
 * a tool being permanently refused: two plugins claiming one name, a name the planner
 * cannot emit, a parameter with no description so the model has to guess it, a permission
 * string that is not a permission so `permission_guard` denies forever. None of them
 * throws; all of them silently degrade the agent.
 */
class BuiltinPluginsTest {

    private val plugins = BuiltinPlugins.STATELESS

    @Test
    fun the_set_is_not_empty_and_is_the_size_the_catalogue_claims() {
        assertTrue(
            "expected the full built-in set, got ${plugins.size}",
            plugins.size >= 70,
        )
    }

    /** A collision means one plugin is unreachable: PluginManager refuses to start at all. */
    @Test
    fun every_plugin_name_is_unique() {
        val duplicates = plugins.groupingBy { it.name }.eachCount().filterValues { it > 1 }
        assertTrue("duplicate plugin names: ${duplicates.keys}", duplicates.isEmpty())
    }

    /**
     * The name is what the model emits in a tool call and what appears in config file
     * names and the audit log. Anything else cannot be routed.
     */
    @Test
    fun every_name_is_lowercase_snake_case() {
        val pattern = Regex("^[a-z0-9]+(_[a-z0-9]+)*$")
        val bad = plugins.filterNot { pattern.matches(it.name) }.map { it.name }
        assertTrue("names the planner cannot emit: $bad", bad.isEmpty())
    }

    @Test
    fun every_description_says_what_the_plugin_is_for() {
        val blank = plugins.filter { it.description.isBlank() }.map { it.name }
        assertTrue("plugins with no description cannot be routed: $blank", blank.isEmpty())

        val tooShort = plugins.filter { it.description.trim().length < 20 }.map { it.name }
        assertTrue(
            "a description this short does not tell the model when to use it: $tooShort",
            tooShort.isEmpty(),
        )
    }

    /**
     * The parameter description is the only specification the model gets for that field.
     * An undocumented parameter is a parameter the model has to guess, and guessing is
     * how a message ends up sent to the wrong address.
     */
    @Test
    fun every_declared_parameter_has_a_description() {
        val undocumented = plugins.flatMap { plugin ->
            plugin.parameters.properties.filter { it.value.description.isBlank() }
                .map { "${plugin.name}.${it.key}" }
        }
        assertTrue("parameters with no description: $undocumented", undocumented.isEmpty())
    }

    @Test
    fun every_required_parameter_is_also_declared() {
        val broken = plugins.filter { plugin ->
            plugin.parameters.required.any { it !in plugin.parameters.properties }
        }.map { it.name }
        assertTrue("schemas requiring undeclared keys: $broken", broken.isEmpty())
    }

    /**
     * A permission string that is not a permission is never granted, so the plugin would
     * be refused forever with a message that looks like the user said no.
     */
    @Test
    fun every_declared_permission_looks_like_an_android_permission() {
        val suspicious = plugins.flatMap { plugin ->
            plugin.requiredPermissions.filterNot { it.contains("permission.") }
                .map { "${plugin.name}: $it" }
        }
        assertTrue("these are not Android permission strings: $suspicious", suspicious.isEmpty())
    }

    @Test
    fun every_category_has_at_least_one_plugin() {
        val used = plugins.map { it.category }.toSet()
        val empty = PluginCategory.entries.filterNot { it in used }.map { it.name }
        assertTrue(
            "categories nothing is registered under would show as an empty screen: $empty",
            empty.isEmpty(),
        )
    }

    /**
     * permission_guard is how the agent answers "what are you allowed to do" before it
     * tries anything, so it has to be in the set the planner is handed first.
     */
    @Test
    fun permission_guard_is_registered_first() {
        assertEquals("permission_guard", plugins.first().name)
    }

    /** Each schema has to survive being sent to the model and parsed back. */
    @Test
    fun every_schema_survives_a_json_round_trip() {
        val broken = plugins.filter { plugin ->
            val restored = JsonSchema.fromJson(plugin.parameters.toJson())
            restored.properties.keys.sorted() != plugin.parameters.properties.keys.sorted() ||
                restored.required.sorted() != plugin.parameters.required.sorted() ||
                // a constraint that survives the trip as a different type stops being enforced
                restored.properties.values.map { it.type }.sorted() !=
                plugin.parameters.properties.values.map { it.type }.sorted()
        }.map { it.name }
        assertTrue("schemas that do not round-trip: $broken", broken.isEmpty())
    }

    @Test
    fun every_plugin_produces_a_catalogue_line_for_the_prompt() {
        val blank = plugins.filter { it.parameters.toPromptHint().isBlank() }.map { it.name }
        assertTrue("plugins with no usable prompt hint: $blank", blank.isEmpty())
    }

    @Test
    fun names_returns_every_registered_plugin_sorted() {
        val persona = PersonaAccess({ Persona.DEFAULT }, {})
        assertEquals(
            plugins.map { it.name }.sorted(),
            BuiltinPlugins.names(persona),
        )
    }

    /**
     * Two builds of the set have to agree, or what the planner is told exists and what
     * the manager can actually route would drift apart.
     */
    @Test
    fun rebuilding_the_set_yields_the_same_plugins() {
        val persona = PersonaAccess({ Persona.DEFAULT }, {})
        assertEquals(
            BuiltinPlugins.all(persona).map { it.name }.sorted(),
            plugins.map { it.name }.sorted(),
        )
    }
}
