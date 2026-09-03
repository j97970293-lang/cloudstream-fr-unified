import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream.gradle:gradle:32895ae")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // Remplacer par VOTRE dépôt une fois forké / poussé
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "j97970293-lang/cloudstream-fr-unified")
    }

    android {
        namespace = "com.lagradost.frunified"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            // Rhino 1.9.1 utilise des API Java 9+ (Map.entry, List.of…) :
            // le desugaring les rend compatibles avec les vieux Androïds.
            isCoreLibraryDesugaringEnabled = true
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val compileOnly by configurations
        val coreLibraryDesugaring by configurations

        // Classes fournies par l'application CloudStream (Plugin, coroutines, okhttp…)
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        compileOnly("com.github.recloudstream.cloudstream:library:-SNAPSHOT")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")

        // Moteur JavaScript pour les scrapeurs Nuvio (Gowaru, Phisher…)
        // Rhino 1.9.1 patché lokalement : yield non parenthésé en argument + call-spread
        // + compat Android (API Java 9+ remplacées). Source : mozilla/rhino tag Rhino1_9_1_Release.
        implementation(files("libs/rhino-nuvio-1.9.1.jar"))
        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    }

    // ------------------------------------------------------------------
    // CRUCIAL : le tâche compileDex du plugin CloudStream ne déxe QUE la
    // sortie de compileDebugKotlin — les jars de libs/ ne sont JAMAIS
    // embarqués (cause du « 0 serveur Nuvio » : NoClassDefFoundError
    // sur org.mozilla.javascript.*). On extrait donc les classes du jar
    // Rhino et on les ajoute aux entrées de compileDex.
    // ------------------------------------------------------------------
    // Sync (et non Copy) : efface les classes d'un build précédent, sinon les
    // anciennes org/mozilla/javascript (v5-v7) restaient dans build/rhino-classes
    // et étaient déxées en double avec com/frunified/rhino.
    val extractRhino by tasks.registering(Sync::class) {
        from(zipTree(project.file("libs/rhino-embed-1.9.1.jar")))
        into(layout.buildDirectory.dir("rhino-classes"))
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    afterEvaluate {
        tasks.withType<com.lagradost.cloudstream3.gradle.tasks.CompileDexTask>().configureEach {
            dependsOn(extractRhino)
            input.from(layout.buildDirectory.dir("rhino-classes"))
        }
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
