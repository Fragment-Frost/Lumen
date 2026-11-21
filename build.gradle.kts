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

/**
 * 区块构建线程数自适应公式的自测。
 *
 * <p>重点验证 {@code builders >= workers} 这个唯一会导致静默死锁的不变量：
 * {@code allocateRenderBuilder()} 是无条件的 {@code take()}，池子小于 worker 数
 * 就会有 worker 永久卡死，且不崩溃、不报错。
 */
val chunkTuningSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates the adaptive chunk-builder thread formula."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.ChunkTuningSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 光照引擎的开关语义与冲突降级自测。
 *
 * <p>注意：光照<b>传播</b>的正确性无法在裸 JVM 上验证（需要完整 World/Chunk），
 * 必须进游戏确认。这个任务只覆盖可直接验证的不变式。
 */
val lightEngineSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates light engine gating and mod-conflict downgrade."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.LightEngineSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 校验纯客户端 mixin 是否都被正确门控。
 *
 * <p>自动发现插件会注册 mixin 包下的每一个类。纯客户端 mixin 若未门控，
 * 专用服务器会因目标类缺失而启动失败，且堆栈全是 ASM 帧，几乎无法定位。
 * 这个检查把这类错误提前到构建期。
 */
val mixinGatingSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Ensures every client-only mixin is gated out on dedicated servers."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.MixinGatingSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 校验配置界面真的接上了 Forge 的 IModGuiFactory 契约。
 *
 * <p>这条检查来自一次真实故障：类没有 implements 该接口，只写了同名方法，
 * 导致 Mods 页面的 Config 按钮<b>永久变灰</b>，且编译/启动全程无任何提示。
 * Forge 判的是 isAssignableFrom，不是方法名，所以必须用反射验证。
 */
val configGuiSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Ensures the Forge config GUI is reachable (i.e. IModGuiFactory is really implemented)."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.ConfigGuiSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 光照引擎的<b>队列语义</b>回归测试。
 *
 * <p>守住一个真实发生过的 bug：减光 BFS 的判断里多了一个 OR 分支，
 * 导致「天空光 15 被移除」时无条件清零所有邻居，级联把整片天空照亮区
 * 清成 0 —— 表现就是"大批量方块出现后阴影渲染错误"。
 *
 * <p>测的是生产代码实际调用的 LightEngineQueueLogic（纯函数），
 * 而不是测试里复制的一份逻辑。
 */
val lightEngineSemanticsSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Guards the light-queue decrease semantics (the sky-light cascade bug)."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.LightEngineSelfTest2")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 区块构建三档模式（线程化 / 半堵塞 / 全堵塞）的自测。
 *
 * <p>重点验证 {@code builders >= workers} 在所有档位下都成立 ——
 * 违反它会导致 worker 永久阻塞在 {@code allocateRenderBuilder()}，
 * 区块不再加载且<b>没有任何报错</b>。
 */
val chunkBuildModeSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates the three chunk-build wait modes and the builders>=workers invariant."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.ChunkBuildModeSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 实体渲染范围的自测。
 *
 * <p>守住的是「不能把不该藏的藏了」：玩家自己、其它玩家、骑乘目标、
 * 命名实体永远不能被距离限制剔除。这些是语义边界而非可调参数，
 * 破坏后的症状是"偶发看不见别人"，极难与网络/服务端问题区分。
 */
val entityRangeSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Guards the entity render-range hard limits (players/riding/named must never be culled)."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.EntityRangeSelfTest")
    dependsOn(tasks.named("testClasses"))
}

/**
 * 哈希与坐标查表的实证测试。
 *
 * <p>这个测试的价值不在"验证我们的哈希更快"——它反而记录了
 * <b>原版哈希更均匀</b>这个实测结论，以及为什么仍然保留 LongKeyMap
 * （省装箱、省 BlockPos 对象，与哈希质量无关）。
 *
 * <p>真正守住的是开放寻址表的正确性：删除后探测链不能断裂、
 * 扩容后所有键仍可命中。这两处写错会表现为"偶发查不到"，极难复现。
 */
val fastHashSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates LongKeyMap semantics and records the measured hash-distribution comparison."

    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
        mainCompileClasspath
    )

    mainClass.set("top.fpvp.lumen.verify.FastHashSelfTest")
    dependsOn(tasks.named("testClasses"))
}

tasks.named("check") {
    dependsOn(verifyMixinTargets, dispatcherSelfTest, chunkTuningSelfTest,
            chunkBuildModeSelfTest,
            lightEngineSelfTest, lightEngineSemanticsSelfTest,
            mixinGatingSelfTest, configGuiSelfTest, entityRangeSelfTest,
            fastHashSelfTest)
}

// 运行配置：1.8.9 的 launchwrapper 依赖 URLClassLoader（Java 9 起被移除），
// 因此游戏进程必须跑在 Java 8 上。Gradle 自身用 Java 17 执行 Loom，两者互不影响。
val java8Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(8))
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(java8Launcher)
}
