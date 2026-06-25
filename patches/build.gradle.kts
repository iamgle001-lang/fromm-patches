group = "app.morphe.patches.fromm"

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

patches {
    about {
        name = "fromm Patches"
        description = "Patches for the fromm fan app"
        author = "iamgle001-lang"
        contact = "iamgle001@gmail.com"
        website = "https://github.com/iamgle001-lang/fromm-patches"
        source = "https://github.com/iamgle001-lang/fromm-patches"
        license = "GNU General Public License v3.0"
    }
}
