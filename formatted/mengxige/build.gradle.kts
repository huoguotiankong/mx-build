import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MengXiGe"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "梦溪阁"
        lang = "zh"
        baseUrl = "https://book.folongteng.com"
    }

    deeplink {
        path("/..*")
    }
}
