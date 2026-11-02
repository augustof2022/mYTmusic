#!/usr/bin/env python3
"""Run stream-resolution regressions with the same standalone Kotlin compiler as CI."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import urllib.request


def main():
    root = Path(__file__).resolve().parents[1]
    versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text())["versions"]
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    gradle_version = re.search(r"gradle-([\d.]+)-bin", wrapper).group(1)
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    compilers = list(gradle_home.glob(f"wrapper/dists/**/gradle-{gradle_version}/lib/kotlin-compiler-embeddable-*.jar"))
    if len(compilers) != 1:
        raise RuntimeError("Run ./gradlew --version first to prepare the pinned Gradle compiler")
    lib = compilers[0].parent
    stdlib = next(lib.glob("kotlin-stdlib-*.jar"))
    kotlin_version = stdlib.stem.removeprefix("kotlin-stdlib-")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    work = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir())) / "mytmusic-stream-tests"
    work.mkdir(exist_ok=True)

    def jar(group, artifact, version):
        target = work / f"{artifact}-{version}.jar"
        if not target.exists():
            url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{target.name}"
            temporary = target.with_suffix(".download")
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            temporary.replace(target)
        return target

    plugin = jar("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", kotlin_version)
    dependencies = [
        stdlib,
        jar("junit", "junit", "4.13.2"),
        jar("org.hamcrest", "hamcrest-core", "1.3"),
        jar("org.jetbrains.kotlin", "kotlin-test", kotlin_version),
        jar("org.jetbrains.kotlin", "kotlin-test-junit", kotlin_version),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["coroutines-android"]),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["json"]),
        jar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["json"]),
        # Transitive runtime dependencies declared by Ktor 3.6.0.
        jar("org.jetbrains.kotlinx", "kotlinx-io-core-jvm", "0.9.1"),
        jar("org.jetbrains.kotlinx", "kotlinx-io-bytestring-jvm", "0.9.1"),
        jar("org.slf4j", "slf4j-api", "2.0.19"),
        *(jar("io.ktor", name, versions["ktor"]) for name in ["ktor-http-jvm", "ktor-utils-jvm", "ktor-io-jvm"]),
    ]
    source = root / "core/service/kotlinYtmusicScraper/src"
    package = Path("kotlin/com/maxrave/kotlinytmusicscraper")
    sources = [
        source / "commonMain" / package / name
        for name in [
            "extractor/AudioTrackOrder.kt",
            "extractor/ExtractedFormats.kt",
            "models/response/PlayerResponse.kt",
            "models/ResponseContext.kt",
            "models/Thumbnails.kt",
            "models/youtube/YouTubeInitialPage.kt",
            "models/youtube/VideoDetails.kt",
            "models/youtube/Thumbnails.kt",
        ]
    ]
    sources += [
        source / "commonTest" / package / "extractor/AudioTrackOrderTest.kt",
        source / "jvmTest" / package / "extractor/ExtractedFormatsTest.kt",
    ]
    classes = work / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    subprocess.run([
        java, "-Xmx512m", "-cp", os.pathsep.join(map(str, lib.glob("*.jar"))),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        f"-Xplugin={plugin}", "-classpath", os.pathsep.join(map(str, dependencies)),
        "-d", str(classes), *map(str, sources),
    ], check=True)
    subprocess.run([
        java, "-Xmx128m", "-cp", os.pathsep.join(map(str, [classes, *dependencies])),
        "org.junit.runner.JUnitCore",
        "com.maxrave.kotlinytmusicscraper.extractor.AudioTrackOrderTest",
        "com.maxrave.kotlinytmusicscraper.extractor.ExtractedFormatsTest",
    ], check=True)


if __name__ == "__main__":
    main()
