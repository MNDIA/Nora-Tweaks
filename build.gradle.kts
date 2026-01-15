import org.gradle.api.file.DuplicatesStrategy
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("fabric-loom")
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

val minecraftVersion = stonecutter.current.version
val yarnMappings = project.property("yarn_mappings") as String
val loaderVersion = project.property("loader_version") as String
val modVersion = project.property("mod.version") as String
val mavenGroup = project.property("maven_group") as String
val baritoneVersion = "1.21.10"

base {
    archivesName = project.property("archives_base_name") as String
    version = "${minecraftVersion}-${modVersion}"
    group = mavenGroup
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("meteor-maven") { url = uri("https://maven.meteordev.org/releases") }
    maven("meteor-maven-snapshots") { url = uri("https://maven.meteordev.org/snapshots") }
    maven("JitPack") { url = uri("https://jitpack.io") }
    maven("DutiReleases") { url = uri("https://maven.duti.dev/releases") }
    maven("Bawnorton") { url = uri("https://maven.bawnorton.com/releases") }
}

val shade by configurations.creating

configurations.named("implementation") {
    extendsFrom(shade)
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // Meteor / Baritone (match meteor-rejects)
    modImplementation("meteordevelopment:meteor-client:${minecraftVersion}-SNAPSHOT")
    modCompileOnly("meteordevelopment:baritone:${baritoneVersion}-SNAPSHOT") {
        isTransitive = false
    }

    // MixinSquared
    include(modImplementation("com.bawnorton.mixinsquared:mixinsquared-fabric:0.2.0")!!)
    annotationProcessor("com.bawnorton.mixinsquared:mixinsquared-fabric:0.2.0")

    // Cubiomes native bundles - shaded to avoid conflicts with Meteor Rejects
    shade("dev.duti.acheong:cubiomes:1.22.5") { isTransitive = false }
    shade("dev.duti.acheong:cubiomes:1.22.5:linux64") { isTransitive = false }
    shade("dev.duti.acheong:cubiomes:1.22.5:osx") { isTransitive = false }
    shade("dev.duti.acheong:cubiomes:1.22.5:windows64") { isTransitive = false }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion,
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    val shadowJar by getting(ShadowJar::class) {
        configurations = listOf(shade)
        archiveClassifier.set("dev")

        relocate("cubitect", "me.noramibu.tweaks.libs.cubitect")
        relocate("dev.duti.acheong.cubiomes", "me.noramibu.tweaks.libs.cubiomes")
    }

    remapJar {
        dependsOn(shadowJar)
        inputFile.set(shadowJar.archiveFile)
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}

