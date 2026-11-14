import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hikarinagi"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "zh"
        baseUrl = "https://www.hikarinagi.org"
    }

    // Keep the id existing libraries already use, so the rename does not orphan them.
    source {
        name = "Hikarinagi Novel"
        lang = "zh"
        id = 1_148_300_780_955_902_112L
        baseUrl = "https://www.hikarinagi.org"
    }
}
