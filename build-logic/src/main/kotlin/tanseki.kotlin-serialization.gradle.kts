plugins {
    id("tanseki.kotlin-library")
}

// Adds the kotlinx.serialization compiler plugin on top of the standard
// library conventions, for modules that declare @Serializable wire types.
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
