plugins { java }
group = "dev.chunkbench"; version = "1.0.0"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    // Match the version your server runs, e.g. 26.2.build.+ (no -SNAPSHOT since 26.1).
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}
