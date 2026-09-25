import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

android {
    namespace = "com.aiia.plugin.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register("packageAiip") {
    group = "aiia"
    description = "Package plugin.dex and manifest.json as an .aiip archive"
    dependsOn("assembleRelease")
    doLast {
        val output = layout.buildDirectory.file("aiip/example.aiip").get().asFile
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream()).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("manifest.json", "{\"id\":\"example\",\"name\":\"Example\",\"version\":\"1.0\",\"entryClass\":\"com.aiia.plugin.example.ExamplePlugin\",\"permissions\":[],\"apiVersion\":1,\"schemaVersion\":1,\"minApiVersion\":1,\"maxApiVersion\":1}".toByteArray())
            val aar = fileTree(layout.buildDirectory.dir("outputs/aar")).matching { include("*.aar") }.singleFile
            val classesJar = ZipInputStream(aar.inputStream()).use { input ->
                val out = ByteArrayOutputStream()
                var entry = input.nextEntry
                while (entry != null) {
                    if (entry.name == "classes.jar") input.copyTo(out)
                    entry = input.nextEntry
                }
                out.toByteArray()
            }
            val dexOutput = layout.buildDirectory.dir("tmp/packageAiip").get().asFile
            dexOutput.mkdirs()
            val classesFile = dexOutput.resolve("classes.jar").apply { writeBytes(classesJar) }
            val d8 = android.sdkDirectory.resolve("build-tools/35.0.0/d8")
            if (d8.exists()) {
                val result = providers.exec {
                    commandLine(d8.absolutePath, "--output", dexOutput.absolutePath, classesFile.absolutePath)
                }
                result.result.get()
                add("plugin.dex", dexOutput.resolve("classes.dex").readBytes())
            } else {
                add("plugin.dex", classesJar)
            }
            add("assets/example.txt", "AIIA example plugin asset\n".toByteArray())
        }
    }
}
