import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "FantasyNovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "幻梦轻小说"
        lang = "zh"
        baseUrl = "https://www.huanmengacg.com"
    }
}
