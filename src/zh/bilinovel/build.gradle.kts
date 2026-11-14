import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "BiliNovel"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    // The site reader first, under the same name and id existing libraries already use.
    source {
        name = "哔哩轻小说"
        lang = "zh"
        id = 6_832_494_364_245_687_154L
        baseUrl {
            mirrors("https://www.bilinovel.com", "https://www.bilinovel.net")
        }
    }

    // The official app's API as a second, independent source.
    source {
        name = "哔哩轻小说（APP）"
        lang = "zh"
        baseUrl = "https://www.bilinovel.net"
    }

    deeplink {
        host("www.bilinovel.com")
        host("www.bilinovel.net")
        host("www.linovelib.com")
        path("/novel/.*\\.html")
    }
}
