import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.uiToolingPreview)
    // compose ui —— WindowDragArea / Window / FrameWindowScope 等 desktop API
    implementation(libs.compose.ui)
    // material3
    implementation(libs.compose.material3)
    // logback
    implementation(libs.logback.classic)
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    testImplementation(libs.kotlin.testJunit)
}

val appName = "MCFeedbackBriefing"
val appVersion = libs.versions.versions.name.get()
val briefingResources = layout.buildDirectory.dir("generated/briefing-app-resources")

val prepareBriefingLicense by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"))
    into(briefingResources.map { it.dir("common") })
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareBriefingLicense)
}

compose.desktop {
    application {
        mainClass = "com.lemon.mcdevmanagermp.MainKt"

        nativeDistributions {
            modules("java.net.http")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = appName
            packageVersion = appVersion
            appResourcesRootDir.set(briefingResources)

            windows {
                menuGroup = appName
                upgradeUuid = "f78faf01-89de-4d3a-8e0f-f27174e34504"
            }

            macOS {
                // macOS jpackage 要求版本号为 1-3 个整数用点号分隔（如 1.0.0）
                // 不支持前导零、预发布后缀、超过 3 段
                val cleanVersion = appVersion.substringBefore("-").removePrefix("0.")
                packageVersion = cleanVersion.split(".").take(3).joinToString(".")
            }
        }
    }
}

tasks.register<Zip>("packagePortable") {
    group = "released"
    description = "Create a portable zip archive (免安装版)"
    dependsOn("createDistributable")

    from(layout.buildDirectory.dir("compose/binaries/main/app/${appName}")) {
        exclude("**/.data/**", "**/logs/**", "**/downloads/**", "**/.patch_temp/**")
    }
    from(rootProject.file("LICENSE"))
    into(appName)
    archiveFileName.set("$appName-$appVersion-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release/portable"))
}

tasks.register<Copy>("packageInstaller") {
    group = "released"
    description = "Create an MSI installer (安装版)"
    dependsOn("packageMsi")

    from(layout.buildDirectory.dir("compose/binaries/main/msi")) {
        include("*.msi")
    }
    into(layout.buildDirectory.dir("release/msi"))
}
