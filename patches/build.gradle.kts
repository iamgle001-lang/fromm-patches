group = "app.morphe.patches.fromm"

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

patches {
    about {
        name = "fromm Patches"
        description = "Patches for the fromm fan app"
        author = ""
        contact = ""
        website = ""
        source = ""
        license = "GNU General Public License v3.0"
    }
}
