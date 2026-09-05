import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Komiic Plus"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Komiic Plus"
        lang = "zh"

        baseUrl {
            mirrors(
                "https://komiic.com",
                "https://komiic.cc",
            )
        }
    }

    deeplink {
        host("komiic.com")
        host("komiic.cc")
        host("www.komiic.com")
        host("www.komiic.cc")
        path("/comic/..*")
    }
}

android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
