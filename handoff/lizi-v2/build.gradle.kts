import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lizi Manhua"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "栗子漫画"
        lang = "zh"
        id = 4884948409608593004L
        baseUrl = "https://lizimh.com"
    }
}
