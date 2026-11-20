import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ankidroid.android.api")
    alias(libs.plugins.vanniktech.maven.publish)
}

// <AnkiDroid's api version>-ankiquest.<n>; the publish workflow checks the tag against it
group = "org.ankiquest.anki"
version = "2.0.0-ankiquest.1"

configure<LibraryExtension> {
    namespace = "com.ichi2.anki.api"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk =
            libs.versions.apiMinSdk
                .get()
                .toInt()
        buildConfigField("String", "ANKI_PACKAGE_NAME", "\"com.ichi2.anki\"")
    }
    buildTypes {
        debug {
            buildConfigField("String", "ANKI_PACKAGE_NAME", "\"com.ichi2.anki.debug\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlin.test)
}

// AnkiQuest API: published to Maven Central as org.ankiquest.anki:api, signed, with the
// vanniktech plugin as in AnkiQuest's Anki-Android-Backend fork. The coordinates differ from
// AnkiDroid's com.ichi2.anki:api so the two never resolve in place of each other; the packages
// (com.ichi2.anki.api, com.ichi2.anki.FlashCardsContract) stay AnkiDroid's, so clients switch
// by changing the dependency line. See docs/ankiquest-api.md.
mavenPublishing {
    coordinates(group.toString(), "api", version.toString())
    publishToMavenCentral(true) // "true": release automatically once validated
    signAllPublications()

    pom {
        name = "AnkiQuest API"
        description =
            "AnkiDroid's API library for AnkiDroid, AnkiQuest and any app offering AnkiDroid's flashcards provider"
        url = "https://github.com/AnkiQuest/AnkiQuest-Android/tree/main/api"
        inceptionYear = "2015"
        licenses {
            license {
                name = "GNU LESSER GENERAL PUBLIC LICENSE, v3"
                url = "https://github.com/AnkiQuest/AnkiQuest-Android/blob/main/api/COPYING.LESSER"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "float3"
                name = "hill"
                url = "https://github.com/float3"
            }
        }
        scm {
            url = "https://github.com/AnkiQuest/AnkiQuest-Android"
            connection = "scm:git:git://github.com/AnkiQuest/AnkiQuest-Android.git"
            developerConnection = "scm:git:ssh://git@github.com/AnkiQuest/AnkiQuest-Android.git"
        }
    }
}
