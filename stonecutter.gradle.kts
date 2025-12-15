plugins {
    id("dev.kikugie.stonecutter")
    id("fabric-loom") version "1.14-SNAPSHOT" apply false
}

stonecutter active "1.21.10"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"" + property("mod.version") + "\";"
    swaps["minecraft_version"] = "\"" + node.metadata.version + "\";"
    swaps["yarn_mappings"] = "\"" + node.project.property("yarn_mappings") + "\";"
    swaps["loader_version"] = "\"" + node.project.property("loader_version") + "\";"
    swaps["baritone_version"] = "\"" + node.project.property("baritone_version") + "\";"
}

