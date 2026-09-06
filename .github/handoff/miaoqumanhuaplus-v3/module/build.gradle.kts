import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Miaoqu Manhua Plus"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "喵趣漫画 Plus"
        lang = "zh"
        id = 4073761417982452876L
        baseUrl = "https://www.miaoqu.me"
    }
}
