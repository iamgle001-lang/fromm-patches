package app.morphe.patches.fromm.misc.ssl

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

@Suppress("unused")
val sslPinningBypassPatch = resourcePatch(
    name = "SSL pinning bypass",
    description = "Bypasses SSL certificate pinning via manifest flags and disables Sentry.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element

            // debuggable=true makes Android trust user-added CAs automatically.
            app.setAttribute("android:debuggable", "true")

            // Remove the existing networkSecurityConfig so the app falls back to
            // the system default (trust all system CAs, no custom pinning).
            app.removeAttribute("android:networkSecurityConfig")

            // Disable Sentry crash reporting via meta-data.
            listOf(
                "io.sentry.enabled" to "false",
                "io.sentry.dsn" to "",
            ).forEach { (name, value) ->
                val meta = doc.createElement("meta-data")
                meta.setAttribute("android:name", name)
                meta.setAttribute("android:value", value)
                app.appendChild(meta)
            }
        }
    }
}
