import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.hubiplan.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.hubiplan.app"
        // Android 8.0: Benachrichtigungskanäle, einheitliche Hintergrundregeln (Konzept, Frage 12)
        minSdk = 28
        targetSdk = 37
        versionCode = 34
        versionName = "0.30.0"
    }

    // Signatur der Veröffentlichung aus keystore.properties (nur auf dem Rechner des Herausgebers):
    // storeFile, storePassword, keyAlias, keyPassword. Fehlt die Datei, bleibt release unsigniert
    // (F-Droid signiert selbst).
    val keystore = rootProject.file("keystore.properties")
    if (keystore.exists()) {
        val p = Properties().apply { keystore.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    // foss: F-Droid und APK, ohne Google-Dienste (Firebase, Play-Dienste) · play: Google Play, zusätzlich Push über Google
    flavorDimensions += "vertrieb"
    productFlavors {
        create("foss") { dimension = "vertrieb" }
        create("play") {
            dimension = "vertrieb"
            // Firebase-Projekt des Herausgebers aus firebase.properties (nicht im Repo); leer: kein Google-Push
            val firebase = Properties().apply {
                rootProject.file("firebase.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            listOf("app_id", "project_id", "api_key", "sender_id").forEach { key ->
                val value = firebase.getProperty(key, "").replace("\"", "")
                buildConfigField("String", "FIREBASE_" + key.uppercase(), "\"$value\"")
            }
        }
    }

    buildTypes {
        release {
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // F-Droid: keine Abhängigkeits-Metadaten von Google im APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Kotlin ist seit AGP 9 eingebaut (kein eigenes Plugin org.jetbrains.kotlin.android mehr)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.work)
    implementation(libs.androidx.biometric)
    debugImplementation(libs.compose.ui.tooling)
    "playImplementation"(libs.firebase.messaging)
    testImplementation(libs.junit)
}

// Lizenzseite der App (Lizenzaudit 31.10.2026, L01): Beim Bauen jeder Variante entsteht aus
// dem tatsächlichen Abhängigkeitsgraphen die Liste der enthaltenen Bibliotheken mit Lizenz,
// dazu der AGPL-Text aus LICENSE. Die Lizenztexte selbst liegen in src/main/assets/lizenzen/.
// Hat eine Bibliothek keine bekannte Lizenz, bricht der Bau ab, statt still zu schweigen.
abstract class LizenzlisteTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appLicense: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** Lizenz einer Bibliothek; null, wenn unbekannt (dann hier ergänzen, nach Prüfung des Originaltexts). */
    private fun licenseOf(group: String, module: String): String? = when {
        // Proprietäre Bedingungen (Android-SDK-Lizenz), nur in der Variante play
        group == "com.google.android.gms" -> "Android-SDK"
        group == "com.google.firebase" &&
            module in setOf("firebase-iid-interop", "firebase-measurement-connector") -> "Android-SDK"
        group.startsWith("androidx.") -> "Apache-2.0"
        group in setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx") -> "Apache-2.0"
        group in setOf("com.squareup.okhttp3", "com.squareup.okio") -> "Apache-2.0"
        group in setOf("com.google.zxing", "com.journeyapps") -> "Apache-2.0"
        group in setOf("com.google.guava", "com.google.errorprone", "com.google.j2objc", "org.jspecify") -> "Apache-2.0"
        group in setOf("com.google.firebase", "com.google.android.datatransport", "javax.inject") -> "Apache-2.0"
        else -> null
    }

    @TaskAction
    fun write() {
        val lines = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (coordinate in coordinates.get()) {
            val (group, module) = coordinate.split(":")
            val license = licenseOf(group, module)
            if (license == null) unknown += coordinate else lines += "$coordinate\t$license"
        }
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "Lizenz unbekannt für: ${unknown.joinToString()}. " +
                    "Originaltext prüfen und in app/build.gradle.kts (LizenzlisteTask) ergänzen.",
            )
        }
        val dir = outputDir.get().asFile.resolve("lizenzen")
        dir.mkdirs()
        dir.resolve("bibliotheken.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        appLicense.get().asFile.copyTo(dir.resolve("LICENSE.txt"), overwrite = true)
    }
}

/** Alle Bibliotheken (Gruppe:Name:Version) im Graphen unter [root], ohne Stücklisten (BOM). */
fun modulesOf(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val result = sortedSetOf<String>()
    fun walk(component: ResolvedComponentResult) {
        if (!seen.add(component)) return
        val id = component.id
        if (id is ModuleComponentIdentifier && !id.module.endsWith("-bom")) {
            result += "${id.group}:${id.module}:${id.version}"
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
    }
    walk(root)
    return result.toList()
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<LizenzlisteTask>("lizenzliste$suffix") {
            coordinates.set(
                configurations.named("${variant.name}RuntimeClasspath")
                    .flatMap { it.incoming.resolutionResult.rootComponent }
                    .map(::modulesOf),
            )
            appLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, LizenzlisteTask::outputDir)
    }
}
