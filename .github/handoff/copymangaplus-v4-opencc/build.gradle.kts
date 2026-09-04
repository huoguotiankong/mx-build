import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CopyManga Plus"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "拷贝漫画 Plus"
        lang = "zh"
        baseUrl = "https://www.mangacopy.com"
    }

    deeplink {
        host("www.mangacopy.com")
        host("www.copy4000.com")
        host("www.relamanhua.com")
        host("www.relamanua.com")
        path("/comic/..*")
    }
}

dependencies {
    implementation("io.github.laisuk:openccjava:1.4.2")
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
