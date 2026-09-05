import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NoyAcg Plus"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "NoyAcg Plus"
        lang = "zh"
        baseUrl {
            mirrors(
                "https://api.noyteam.online",
                "https://api.noymanga.com",
                "https://api.noy.asia",
            )
        }
    }

    deeplink {
        host("noymanga.com")
        host("beta.noyteam.online")
        path("/manga/..*")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("io.github.laisuk:openccjava:1.4.2")
}
