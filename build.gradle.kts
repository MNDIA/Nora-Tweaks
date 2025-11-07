import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("fabric-loom") version "1.12-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = properties["mod_version"] as String
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        name = "JitPack"
        url = uri("https://jitpack.io")
    }
    maven {
        name = "DutiReleases"
        url = uri("https://maven.duti.dev/releases")
    }
}

val extraLibs = configurations.create("extraLibs")

configurations.named("implementation") {
    extendsFrom(extraLibs)
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:${properties["minecraft_version"] as String}-SNAPSHOT")

    compileOnly(files("libs/baritone-api-1.15.0.jar"))

    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5") {
        isTransitive = false
    }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:linux64") {
        isTransitive = false
    }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:osx") {
        isTransitive = false
    }
    add("extraLibs", "dev.duti.acheong:cubiomes:1.22.5:windows64") {
        isTransitive = false
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to project.property("minecraft_version"),
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
