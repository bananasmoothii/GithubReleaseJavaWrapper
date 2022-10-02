@file:UseSerializers(RegexSerializer::class, PathSerializer::class)

package fr.bananasmoothii.githubreleasejavawrapper

import com.charleskorn.kaml.Yaml
import khttp.get
import khttp.responses.Response
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import org.kohsuke.github.GHRelease
import org.kohsuke.github.GitHub
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import kotlin.io.path.*

fun main() = runBlocking {
    if (!configFile.exists()) {
        println("Config file not found, creating one")
        configFile.createFile()
        configFile.writeText(Thread.currentThread().contextClassLoader.getResource(configFile.name)!!.readText())
        println("Config file created, please fill it and run the program again")
        return@runBlocking
    }
    config = Yaml.default.decodeFromStream(Config.serializer(), configFile.inputStream())

    val github = GitHub.connectUsingOAuth(config.githubToken)

    val release = github.getRepository(config.githubRepo).listReleases()
        .maxBy { it.published_at } ?: throw IllegalStateException("No release found")

    printlnIfNotQuiet { "Using release ${release.name} published at ${release.published_at} Tag: ${release.tagName}\n${release.body}" }

    downloadAssets(release)

    runJars()
}

suspend fun downloadAssets(release: GHRelease) = coroutineScope {
    for (download in config.downloads) launch {
        var foundAsset = false
        for (asset in release.listAssets()) {
            download.matcher = download.fileRegex.matchEntire(asset.name)
            if (download.matcher != null) {
                foundAsset = true
                try {
                    if (asset.id == idFile.readText().toLong()) {
                        printlnIfNotQuiet { "Asset already downloaded" }
                    } else {
                        printlnIfNotQuiet { "Downloading asset" }
                        val response: Response = get(
                            asset.browserDownloadUrl,
                            headers = mapOf("Authorization" to "token ${config.githubToken}")
                        )
                        if (response.statusCode == 200) {
                            Path.of(download.copyFileAt.dollarReplace(download.matcher!!)).outputStream(CREATE, TRUNCATE_EXISTING).use {
                                response.raw.copyTo(it)
                            }
                            printlnIfNotQuiet { "Asset downloaded" }
                        } else {
                            System.err.println("Error while downloading asset: ${response.statusCode} ${response.text}")
                        }
                    }
                } catch (e: Throwable) {
                    System.err.println("Error while downloading asset")
                    e.printStackTrace()
                }
            }
        }
        if (!foundAsset) {
            System.err.println("No asset found for download matching ${download.fileRegex}")
        }
    }
}

fun runJars() {
    for (download in config.downloads) {
        if (download.onDownloadFinish == null) continue
        val command = download.onDownloadFinish.dollarReplace(download.matcher!!)
        printlnIfNotQuiet("Running $command")
        ProcessBuilder(command).start()
    }
}

lateinit var config: Config

@Serializable
data class Config(
    val githubRepo: String,
    val githubToken: String,
    val startCommand: String,
    val downloads: List<Download>,
    val quiet: Boolean = false,
)

@Serializable
data class Download(
    val fileRegex: Regex,
    val copyFileAt: String,
    val onDownloadFinish: String? = null,
) {
    @Transient
    var matcher: MatchResult? = null
}

val configFile: Path = Path.of("GithubReleaseJavaWrapper.yml")

val idFile: Path = Path.of(".GithubReleaseJavaWrapper_current_release_id.txt")

val dollarReplaceRegex = Regex("(?<!\\\\)\\$((\\d+)|\\{(\\d+)})")

fun String.dollarReplace(matcher: MatchResult) = dollarReplaceRegex.replace(this) {
    matcher.groups[(it.groups[2] ?: it.groups[3] ?: throw IllegalStateException("No group found")).value.toInt()]!!.value
}

fun printlnIfNotQuiet(s: String) {
    if (!config.quiet) println(s)
}

fun printlnIfNotQuiet(s: () -> String) {
    if (!config.quiet) println(s())
}
