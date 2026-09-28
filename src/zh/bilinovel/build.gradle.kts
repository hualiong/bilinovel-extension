import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "BiliNovel"
    versionCode = 25
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "哔哩轻小说"
        lang = "zh"
        baseUrl {
            // Generated `baseUrl` + preference screen come from :core's MirrorPreferences
            mirrors("https://www.bilinovel.com", "https://www.bilinovel.net")
        }
    }

    deeplink {
        host("www.bilinovel.com")
        host("www.bilinovel.net")
        host("www.linovelib.com")
        path("/novel/.*\\.html")
    }
}
