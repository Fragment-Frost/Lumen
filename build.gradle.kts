import org.apache.commons.lang3.SystemUtils

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    // 8.1.1 要求 Gradle 8+，而本项目固定在 Gradle 7.6，故使用 7.1.2。
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

//Constants:

val baseGroup: String by project
val mcVersion: String by project
val version: String by project
val mixinGroup = "$baseGroup.mixin"
val modid: String by project
val transformerFile = file("src/main/resources/accesstransformer.cfg")

// Toolchains:
// Loom 本身在 Java 17 上运行，产物目标是 Java 8。
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

// Minecraft configuration:
loom {
    log4jConfigs.from(file("log4j2.xml"))
    launchConfigs {
        "client" {
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                // This argument causes a crash on macOS
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        mixinConfig("mixins.$modid.json")
        if (transformerFile.exists()) {
            println("Installing access transformer")
            accessTransformer(transformerFile)
        }
    }
    mixin {
        defaultRefmapName.set("mixins.$modid.refmap.json")
    }
}

sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

// Dependencies:

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    // If you don't want to log in with your real minecraft account, remove this line
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

/**
 * 用于被打进最终 jar 的依赖。implementation 继承它，因此这些依赖同时也在编译类路径上。
 */
val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    // Mixin 运行时：1.8.9 是 legacy launchwrapper 时代，只能用 0.7.x。
    // jar 必须自带 Mixin（Forge 1.8.9 不提供），因此走 shadowImpl 打包。
    // isTransitive = false 很重要：0.7.11 的 pom 会拖入 net.fabricmc:sponge-mixin:0.8.5，
    // 与 Loom 内置的 0.8.5 注解处理器重复，导致编译期 AbstractMethodError。
    shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }

    // If you don't want to log in with your real minecraft account, remove this line
    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.2.1")
}

// Tasks:

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}

tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(modid)
    manifest.attributes.run {
        this["FMLCorePluginContainsFMLMod"] = "true"
        this["ForceLoadAsMod"] = "true"

        this["TweakClass"] = "org.spongepowered.asm.launch.MixinTweaker"
        this["MixinConfigs"] = "mixins.$modid.json"
        if (transformerFile.exists())
            this["FMLAT"] = "${modid}_at.cfg"
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    inputs.property("basePackage", baseGroup)

    // 资源里有中文，必须显式指定编码，否则 expand 时会被按平台默认编码读成乱码。
    filteringCharset = "UTF-8"

    filesMatching(listOf("mcmod.info", "mixins.$modid.json")) {
        expand(inputs.properties)
    }

    rename("accesstransformer.cfg", "META-INF/${modid}_at.cfg")
}

val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}

tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
    doLast {
        configurations.forEach {
            println("Copying dependencies into mod: ${it.files}")
        }
    }

    // If you want to include other dependencies and shadow them, you can relocate them in here
    fun relocate(name: String) = relocate(name, "$baseGroup.deps.$name")
}

tasks.assemble.get().dependsOn(tasks.remapJar)

// ---------------------------------------------------------------------------
// 构建期校验任务
//
// 这两个任务需要的输入必须在 task 闭包**外面**取好：
// `configurations.shadowImpl` / `configurations.compileClasspath` 是 Kotlin DSL 的
// 委托访问器，只在脚本顶层作用域可见；写进 `tasks.registering { }` 的闭包后，
// 该闭包的接收者变成了任务对象，访问器解析不到，脚本直接编译失败。
// ---------------------------------------------------------------------------

/**
 * Loom 产出的 mapped jar。
 *
 * <p>命名空间直接映射到 Loom 的缓存目录，因此换 Forge/MCP 版本时要同步改。
 * 用 `RegularFile` 而不是 `File`：`Directory#file()` 返回的就是它，
 * 后续 `asFile` 才有意义（对 `File` 调 `.asFile` 是不存在的 API）。
 */
val loomMappedJar = layout.projectDirectory
    .dir(".gradle/loom-cache/1.8.9")
    .dir("de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9")
    .file("minecraft-project-@-mapped.jar")

/** Mixin 运行时（自带 ASM 副本），校验器用它解析 class 文件。 */
val mixinRuntime = shadowImpl

/** 主源码的编译类路径，自测需要它来解析 log4j 等依赖。 */
val mainCompileClasspath = configurations.compileClasspath

/**
 * 校验每个 mixin 的目标方法与注入点是否真的存在于目标类里。
 *
 * <p>为什么值得单独做一个 task：mixin 的 `require = 0` 会让“目标不存在”
 * 这类错误**静默通过** —— 功能完全失效，但游戏照常启动、日志干干净净。
 * 这类 bug 在运行时几乎无法定位，而在构建期只需要几毫秒就能发现。
 *
 * <p>它直接读 Loom 产出的 mapped jar，不启动游戏，因此可以放心挂到 CI 上。
 */
val verifyMixinTargets by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks that every mixin target method and injection point exists."

    classpath = files(layout.buildDirectory.dir("classes/java/test"), mixinRuntime)
    mainClass.set("top.fpvp.lumen.verify.MixinTargetVerifier")
    args("--target-jar", loomMappedJar.asFile.absolutePath)

    // mapped jar 在首次构建前可能还不存在，此时跳过而不是让构建失败。
    onlyIf {
        val present = loomMappedJar.asFile.isFile
        if (!present) {
            logger.lifecycle("verifyMixinTargets 跳过：未找到 ${loomMappedJar.asFile}")
        }
        present
    }

    dependsOn(tasks.named("testClasses"))
}

/**
 * 异步调度器的并发行为自测。
 *
 * <p>调度器是项目里唯一真正涉及多线程的部分，也是最容易出现
 * “不崩溃、不报错、只是悄悄卡住”的地方。这些用例不需要 Minecraft 运行时，
 * 可以直接在普通 JVM 上跑，因此适合放进 CI。
 *
 * <p>覆盖：队列满不阻塞主线程、任务不丢失、异常不杀 worker、
 * 代次切换丢弃旧结果、后台任务不被饿死。
 */
val dispatcherSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the async dispatcher concurrency self-tests."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.DispatcherSelfTest")
    dependsOn(tasks.named("testClasses"))
}

tasks.named("check") {
    dependsOn(verifyMixinTargets, dispatcherSelfTest)
}

// 运行配置：1.8.9 的 launchwrapper 依赖 URLClassLoader（Java 9 起被移除），
// 因此游戏进程必须跑在 Java 8 上。Gradle 自身用 Java 17 执行 Loom，两者互不影响。
val java8Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(8))
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(java8Launcher)
}
