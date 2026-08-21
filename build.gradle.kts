plugins {
    id("maven-publish")
    id("net.fabricmc.fabric-loom") version "1.16.3" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.16.3" apply false

    // https://github.com/ReplayMod/preprocessor
    // https://github.com/Fallen-Breath/preprocessor
    // https://jitpack.io/#Fallen-Breath/preprocessor
    id("com.replaymod.preprocess") version "c5abb4fb12"
}

preprocess {
    strictExtraMappings.set(false)

    val mc12111 = createNode("1.21.11", 1_21_11, "mojang")
    val mc260102 = createNode("26.1.2", 26_01_02, "mojang")

    mc12111.link(mc260102, file("versions/mapping-1.21.11-26.1.2.txt"))

    // See https://github.com/Fallen-Breath/fabric-mod-template/blob/1d72d77a1c5ce0bf060c2501270298a12adab679/build.gradle#L55-L63
    for (node in getNodes()) {
        findProject(node.project)
            ?.ext
            ?.set("mcVersion", node.mcVersion)
    }
}
