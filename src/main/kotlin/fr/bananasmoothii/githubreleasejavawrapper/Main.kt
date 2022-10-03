@file:UseSerializers(RegexSerializer::class)

package fr.bananasmoothii.githubreleasejavawrapper

import com.charleskorn.kaml.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import org.kohsuke.github.GitHub
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
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

    val github: GitHub = if (config.githubToken.isNotBlank()) GitHub.connectUsingOAuth(config.githubToken) else GitHub.connectAnonymously()

    downloadAssets(github)

    runJars()
}

suspend fun downloadAssets(github: GitHub) = coroutineScope {
    for (download in config.downloads) launch(Dispatchers.IO) {
        var foundAsset = false

        val release = github.getRepository(download.githubRepo).listReleases()
            .maxBy { it.published_at } ?: throw IllegalStateException("No release found")

        printlnIfNotQuiet { "Using release ${release.name} published at ${release.published_at} Tag: ${release.tagName}\n${release.body}" }

        for (asset in release.listAssets()) {
            val matcher = download.fileRegex.matchEntire(asset.name)
            if (matcher != null) {
                foundAsset = true
                download.matcher = matcher
                try {
                    val file = Path.of(download.copyFileAt.dollarReplace(download.matcher!!))
                    if (file.exists() && asset.id == idFile.let { if (it.exists()) it else null }?.readText()?.toLong()) {
                        printlnIfNotQuiet("Asset already downloaded")
                    } else {
                        printlnIfNotQuiet("Downloading asset...")
                        httpClient.send(
                            HttpRequest.newBuilder(asset.url.toURI())
                                .header("Authorization", "token ${config.githubToken}")
                                .header("Accept", "application/octet-stream")
                                .GET()
                                .build(),
                            BodyHandlers.ofString()
                        ).headers().firstValue("location").get()
                            .let {
                                httpClient.send(
                                    HttpRequest.newBuilder(URI(it))
                                        .header("Authorization", "token ${config.githubToken}")
                                        .header("Accept", "application/octet-stream")
                                        .GET()
                                        .build(),
                                    BodyHandlers.ofFile(file)
                                )
                            }
                        idFile.writeText(asset.id.toString(), options = arrayOf(CREATE, TRUNCATE_EXISTING))
                        printlnIfNotQuiet("Asset downloaded")
                    }
                } catch (e: Throwable) {
                    System.err.println("Error while downloading asset")
                    e.printStackTrace()
                }
            }
            break
        }
        if (!foundAsset) {
            System.err.println("No asset found for download matching ${download.fileRegex}")
        }
    }
}

fun runJars() {
    for (download in config.downloads) {
        if (download.onDownloadFinish == null) continue
        val command =
            if (download.onDownloadFinish.isBlank()) mutableListOf()
            else download.onDownloadFinish.dollarReplace(download.matcher!!).split(" ").toMutableList()
        download.onDownloadFinishArgs.mapTo(command) { it.dollarReplace(download.matcher!!) }
        printlnIfNotQuiet("Running $command")
        ProcessBuilder("java")
        ProcessBuilder(command)
            .directory(File("."))
            .inheritIO()
            .start()
    }
}

lateinit var config: Config

@Serializable
data class Config(
    val githubToken: String,
    val downloads: List<Download>,
    val quiet: Boolean = false,
)

@Serializable
data class Download(
    val githubRepo: String,
    val fileRegex: Regex,
    val copyFileAt: String,
    val onDownloadFinish: String? = null,
    val onDownloadFinishArgs: List<String> = emptyList(),
) {
    @Transient
    var matcher: MatchResult? = null
}

val httpClient: HttpClient = HttpClient.newBuilder().build()

val configFile: Path = Path.of("GithubReleaseJavaWrapper.yml")

val idFile: Path = Path.of(".GithubReleaseJavaWrapper_current_release_id.txt")

val dollarReplaceRegex = Regex("((?<!\\\\)(?:\\\\\\\\)*)\\$((\\d+)|\\{(\\d+)})")

fun String.dollarReplace(matcher: MatchResult) = dollarReplaceRegex.replace(this) {
    it.groupValues[1] + matcher.groups[(it.groups[3] ?: it.groups[4] ?: throw IllegalStateException("No group found")).value.toInt()]!!.value
}

fun printlnIfNotQuiet(s: String) {
    if (!config.quiet) println(s)
}

fun printlnIfNotQuiet(s: () -> String) {
    if (!config.quiet) println(s())
}
