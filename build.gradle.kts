import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("fabric-loom")
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
}

val extraLibs = configurations.create("extraLibs")

configurations.named("implementation") {
    extendsFrom(extraLibs)
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

    // Cubiomes native bundles
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5") { isTransitive = false }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:linux64") { isTransitive = false }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:osx") { isTransitive = false }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:windows64") { isTransitive = false }
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

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        from({
            configurations["extraLibs"].filter { it.exists() }.map { file ->
                if (file.isDirectory) file else zipTree(file)
            }
        })

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
