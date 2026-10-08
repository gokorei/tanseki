plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    api(project(":core:domain"))
    // Frontmatter is parsed as YAML, not as a subset of it. The engine is the
    // reference implementation and returns plain collections, which is what an
    // open-ended key space needs: a document may declare any key, so the parse
    // has to be schema-less. Frontmatter is untrusted input (vault import, the
    // HTTP seam), so FrontmatterCodec drives it through the safe loader.
    api(libs.snakeyaml.engine)
}
