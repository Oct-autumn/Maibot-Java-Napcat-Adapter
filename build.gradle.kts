import java.security.MessageDigest

plugins {
    id("java")
}

// **Development only**
// This variable is used to move the generated jar to a specific location after build.
// If set to empty, it will not move the jar.
val moveJarTo = "../MaiBot-java/core/mods"

/**
 * Mod Properties Object
 *
 * This class contains metadata for the Maibot Mod.
 * Configure the mod's information and dependencies here.
 *
 * All these properties will be generated into the META-INF/mod.toml file,
 * which is used by the Maibot Mod Loader to identify and load the mod.
 */
class ModProperties {
    companion object {
        // Mod ID
        // The unique identifier for this mod.
        // You should change this to your mod's ID.
        const val MOD_ID = "napcat_adapter"

        // Mod Version
        // The version info of this mod.
        // Format: MAJOR.MINOR.PATCH-PRERELEASE
        // Note that build metadata will be appended automatically during the build process. (Not show in jar file name)
        // So you don't need to add it here.
        // You should change this to your mod's version.
        const val MOD_VERSION = "0.1.0-Alpha"

        // Mod Package Name
        // The root package name for this mod.
        // For example, if your mod's file are under "org.example.mymod", set this to "org.example.mymod".
        // You should change this to your mod's package name.
        const val MOD_PACKAGE_NAME = "org.maibot.mods.ncada"

        // SDK Version
        // The version range of the SDK that this mod qualified.
        // If your mod depends on a specific SDK version, just put that version here.
        // (e.g., "1.0.0" means it only works with SDK version 1.0.0)
        // If your mod works with a range of SDK versions, you can specify it like "[1.0.0,2.0.0)".
        // (e.g., "[1.0.0,2.0.0)" means it works with SDK versions from 1.0.0 (inclusive) to 2.0.0 (exclusive))
        const val MOD_SDK_VERSION = "0.1.0-Alpha"

        // Mod License (recommended)
        // The open source license used by this mod.
        // If your mod is open source, it's recommended to specify the license here.
        // Examples: "MIT", "Apache-2.0", "GPL-3.0"
        const val MOD_LICENSE = "MIT"

        // Mod URL (optional)
        // The URL of this mod's homepage or repository.
        const val MOD_URL = ""

        fun getDependencies(): List<ModDependencies> {
            return listOf(
                // Mod Dependencies
                // If your mod depends on other mods, add them here.
                // Example:
                // To add a mandatory dependency on a mod with ID "another_mod_id" and version "1.0.0":
                // ModDependencies("another_mod_id", "1.0.0", true),
            )
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.17")

    // Netty for networking
    implementation("io.netty:netty-all:4.2.6.Final")

    // Jackson Annotations
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.20")

    // Jackson for JSON parsing
    implementation("tools.jackson.core:jackson-databind:3.0.1")

    // SDK Dependency
    implementation(files("../MaiBot-java/sdk/build/libs/sdk-0.1.0-Alpha.jar"))
}

// Create mod.toml
// If you don't know what this is, please don't edit it.
tasks.register("createModToml") {
    val outputDir = file("src/main/resources/META-INF")
    val outputFile = file("$outputDir/mod.toml")
    val modDependencies = ModProperties.getDependencies()
    doLast {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        if (ModProperties.MOD_ID.isBlank()) {
            throw IllegalArgumentException("Mod ID cannot be null or empty")
        } else if (Regex("""^[0-9a-zA-Z-_]+$""").matches(ModProperties.MOD_ID).not()) {
            throw IllegalArgumentException("Mod ID contains illegal characters: ${ModProperties.MOD_ID}")
        }
        if (ModProperties.MOD_VERSION.isEmpty()) {
            throw IllegalArgumentException("Mod Version cannot be null or empty")
        }
        if (ModProperties.MOD_PACKAGE_NAME.isEmpty()) {
            throw IllegalArgumentException("Mod Main Class cannot be null or empty")
        }
        if (ModProperties.MOD_SDK_VERSION.isEmpty()) {
            throw IllegalArgumentException("Mod SDK Version cannot be null or empty")
        }
        if (isValidVersionRange(ModProperties.MOD_SDK_VERSION).not()) {
            throw IllegalArgumentException("Mod SDK Version range format is invalid: ${ModProperties.MOD_SDK_VERSION}")
        }

        val innerVersion = "${ModProperties.MOD_VERSION}+${calcSrcHash().substring(0, 8)}"

        var modTomlContent = """
            mod_id = "${ModProperties.MOD_ID}"
            version = "$innerVersion"
            package_name = "${ModProperties.MOD_PACKAGE_NAME}"
            sdk_version = "${ModProperties.MOD_SDK_VERSION}"
            license = "${ModProperties.MOD_LICENSE}"
            url = "${ModProperties.MOD_URL}"
            """.trimIndent()

        if (modDependencies.isNotEmpty()) {
            var dependencyContent = StringBuilder()
            modDependencies.forEach { dep ->
                dependencyContent.append('\n')
                dependencyContent.append(
                    """
                    [[dependencies]]
                    mod_id = "${dep.id}"
                    version = "${dep.version}"
                    mandatory = ${dep.mandatory}
                    """.trimIndent()
                )
            }
            modTomlContent += dependencyContent.toString()
        }

        outputFile.writeText(modTomlContent)
    }
}

tasks.jar {
    archiveBaseName.set(ModProperties.MOD_ID)
    archiveVersion.set(ModProperties.MOD_VERSION)
}

tasks.register("jarAndMove") {
    dependsOn("jar")

    doLast {
        // **Development only**
        // Move the generated jar to a specific location for easier testing.
        if (moveJarTo.isNotEmpty()) {
            val jarFile = tasks.getByName("jar").outputs.files.singleFile
            val destFile = file("$moveJarTo/${jarFile.name}")
            jarFile.copyTo(destFile, overwrite = true)
            println("Moved jar to: ${destFile.absolutePath}")
        }
    }
}

tasks.named("processResources") {
    dependsOn("createModToml")
}

tasks.test {
    useJUnitPlatform()
}

fun isValidVersionRange(versionRange: String): Boolean {
    if (versionRange == "*") return true

    val verRangeRegex = Regex("""^[\[(](?<lb>[0-9a-zA-Z-+.]*), ?(?<rb>[0-9a-zA-Z-+.]*)[)\]]$""")
    val semverRegex =
        Regex("""^(0|[1-9]+[0-9]*)\.(0|[1-9]+[0-9]*)\.(0|[1-9]+[0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$""")

    val rangeMatch = verRangeRegex.find(versionRange)
    if (rangeMatch != null) {
        val lb = rangeMatch.groups["lb"]?.value
        val rb = rangeMatch.groups["rb"]?.value
        if (lb != null && lb.isNotEmpty() && !semverRegex.matches(lb)) {
            return false
        }
        if (rb != null && rb.isNotEmpty() && !semverRegex.matches(rb)) {
            return false
        }
    } else if (semverRegex.matches(versionRange).not()) {
        return false
    }

    return true
}

/**
 * Mod Dependencies Data Class
 */
class ModDependencies {
    var id: String
    var version: String
    var mandatory: Boolean

    constructor(id: String, versionRange: String, mandatory: Boolean) {
        if (id.isEmpty()) {
            throw IllegalArgumentException("Mod dependency ID cannot be null or empty")
        }
        if (versionRange.isEmpty()) {
            throw IllegalArgumentException("Mod dependency version cannot be null or empty")
        }

        if (!versionRange.equals("*")) {
            // Simple validation for version range format
            if (isValidVersionRange(versionRange).not()) {
                throw IllegalArgumentException("Mod dependency version range format is invalid: $versionRange")
            }
        }

        this.id = id
        this.version = versionRange
        this.mandatory = mandatory
    }
}

// Calculate source code hash for build identification
fun calcSrcHash(): String {
    val srcDir = file("src/main/java")
    val digest = MessageDigest.getInstance("SHA-256")

    srcDir.walkTopDown().filter { it.isFile }.forEach { file ->
        file.inputStream().use { fis ->
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
}