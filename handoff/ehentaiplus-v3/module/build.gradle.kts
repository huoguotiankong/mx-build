import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai Plus"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "E-Hentai Plus"
        lang = "zh"
        baseUrl = "https://e-hentai.org"
    }

    deeplink {
        host("e-hentai.org")
        host("exhentai.org")
        path("/g/..*/..*")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
