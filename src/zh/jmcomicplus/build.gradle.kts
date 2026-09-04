import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "JMComic Plus"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "禁漫天堂 Plus"
        lang = "zh"
        baseUrl = "https://18comic.vip"
    }

    deeplink {
        host("18comic.vip")
        host("18comic.ink")
        host("jmcomic-zzz.one")
        host("jmcomic-zzz.org")
        path("/album/..*")
        path("/photo/..*")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
