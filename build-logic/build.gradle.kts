plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

// compileOnly: AGP classes are available at compile time; at runtime the including
// project's AGP classpath is used (avoiding duplicate-classpath conflicts).
dependencies {
    compileOnly("com.android.tools.build:gradle:9.1.1")
}

gradlePlugin {
    plugins {
        create("ffmpegLibraryPatch") {
            id = "ffmpeg-library-patch"
            implementationClass = "FfmpegLibraryPatchPlugin"
        }
    }
}
