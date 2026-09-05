import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Dongman Manhua Plus"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "咚漫画 Plus"
        lang = "zh-Hans"
        baseUrl {
            mirrors(
                "https://m.dongmanmanhua.cn",
                "https://www.dongmanmanhua.cn",
            )
        }
    }

    deeplink {
        host("m.dongmanmanhua.cn")
        host("www.dongmanmanhua.cn")
        path("/..*/list")
        path("/episodeList")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
