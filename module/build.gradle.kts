import android.databinding.tool.ext.capitalizeUS
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.cmaker)
}

val moduleId: String by rootProject.extra
val moduleName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val commitHash: String by rootProject.extra
val abiList: List<String> by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra

val releaseFlags = arrayOf(
    "-O3", "-flto",
    "-Wno-unused", "-Wno-unused-parameter",
    "-Wl,--exclude-libs,ALL", "-Wl,-icf=all,--lto-O3", "-Wl,-s,-x,--gc-sections"
)

android {
    defaultConfig {
        ndk {
            abiFilters.addAll(abiList)
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            version = "3.28.0+"
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

cmaker {
    default {
        val cmakeArgs = arrayOf(
            "-DANDROID_STL=none",
            "-DMODULE_NAME=$moduleId",
        )
        arguments += cmakeArgs
        abiFilters("arm64-v8a", "x86_64")
    }
    buildTypes {
        when (it.name) {
            "release" -> {
                cppFlags += releaseFlags
                cFlags += releaseFlags
            }
        }
        val commonFlags = arrayOf(
            // Silent noisy warnings
            "-Wno-reorder-ctor",
            "-Wno-overloaded-virtual",
            "-Wno-unused-function",
            "-Wno-unused-but-set-variable",
            "-Wno-unused-private-field",
            "-Wno-missing-braces",
            "-Wno-delete-non-abstract-non-virtual-dtor",
            "-Wno-unused-variable",
            "-Wno-sometimes-uninitialized",
            "-Wno-logical-op-parentheses",
            "-Wno-shift-count-overflow",
            "-Wno-deprecated-declarations",
            "-Wno-infinite-recursion",
            "-Wno-format",
            "-Wno-deprecated-volatile",
        )
        cppFlags += commonFlags
        cFlags += commonFlags
    }
}

dependencies {
    compileOnly(libs.cxx)
}

androidComponents.onVariants { variant ->
    afterEvaluate {
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.capitalizeUS()
        val buildTypeLowered = variant.buildType?.lowercase()
        val supportedAbis = abiList.map {
            when (it) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a" -> "arm"
                "x86" -> "x86"
                "x86_64" -> "x64"
                else -> error("unsupported abi $it")
            }
        }.joinToString(" ")

        val moduleDir = layout.buildDirectory.file("outputs/module/$variantLowered")
        val zipFileName =
            "$moduleName-$verName-$verCode-$commitHash-$buildTypeLowered.zip".replace(' ', '-')

        val prepareModuleFilesTask = task<Sync>("prepareModuleFiles$variantCapped") {
            group = "module"
            dependsOn("assemble$variantCapped", ":service:assemble$variantCapped")
            into(moduleDir)
            from(rootProject.layout.projectDirectory.file("README.md"))
            from(layout.projectDirectory.file("template")) {
                exclude("module.prop", "customize.sh", "post-fs-data.sh", "service.sh", "daemon")
                filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
            }
            from(layout.projectDirectory.file("template")) {
                include("module.prop")
                expand(
                    "moduleId" to moduleId,
                    "moduleName" to moduleName,
                    "versionName" to "$verName ($verCode-$commitHash-$variantLowered)",
                    "versionCode" to verCode
                )
            }
            from(layout.projectDirectory.file("template")) {
                include("customize.sh", "post-fs-data.sh", "service.sh", "daemon")
                val tokens = mapOf(
                    "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                    "SONAME" to moduleId,
                    "SUPPORTED_ABIS" to supportedAbis,
                    "MIN_SDK" to androidMinSdkVersion.toString()
                )
                filter<ReplaceTokens>("tokens" to tokens)
                filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
            }
            from(project(":service").layout.buildDirectory.file("outputs/apk/$variantLowered/service-$variantLowered.apk")) {
                rename { "service.apk" }
            }
            from(layout.buildDirectory.file("intermediates/stripped_native_libs/$variantLowered/strip${variantCapped}DebugSymbols/out/lib")) {
                exclude("**/libbinder.so", "**/libutils.so")
                into("lib")
            }

            doLast {
                fileTree(moduleDir).visit {
                    if (isDirectory) return@visit
                    val md = MessageDigest.getInstance("SHA-256")
                    file.forEachBlock(4096) { bytes, size ->
                        md.update(bytes, 0, size)
                    }
                    file(file.path + ".sha256").writeText(
                        org.apache.commons.codec.binary.Hex.encodeHexString(
                            md.digest()
                        )
                    )
                }
            }
        }

        val zipTask = task<Zip>("zip$variantCapped") {
            group = "module"
            dependsOn(prepareModuleFilesTask)
            archiveFileName.set(zipFileName)
            destinationDirectory.set(layout.projectDirectory.file("release").asFile)
            from(moduleDir)
        }

        val pushTask = task<Exec>("push$variantCapped") {
            group = "module"
            dependsOn(zipTask)
            commandLine("adb", "push", zipTask.outputs.files.singleFile.path, "/data/local/tmp")
        }

        val installKsuTask = task<Exec>("installKsu$variantCapped") {
            group = "module"
            dependsOn(pushTask)
            commandLine(
                "adb", "shell", "su", "-c",
                "/data/adb/ksud module install /data/local/tmp/$zipFileName"
            )
        }

        val installMagiskTask = task<Exec>("installMagisk$variantCapped") {
            group = "module"
            dependsOn(pushTask)
            commandLine(
                "adb",
                "shell",
                "su",
                "-M",
                "-c",
                "magisk --install-module /data/local/tmp/$zipFileName"
            )
        }

        task<Exec>("installKsuAndReboot$variantCapped") {
            group = "module"
            dependsOn(installKsuTask)
            commandLine("adb", "reboot")
        }

        task<Exec>("installMagiskAndReboot$variantCapped") {
            group = "module"
            dependsOn(installMagiskTask)
            commandLine("adb", "reboot")
        }
    }
}
