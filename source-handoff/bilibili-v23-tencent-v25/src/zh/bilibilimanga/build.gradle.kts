import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Bilibili Manga"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "哔哩哔哩漫画"
        lang = "zh"
        baseUrl = "https://manga.bilibili.com"
    }

    deeplink {
        host("manga.bilibili.com")
        path("/detail/mc.*")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
