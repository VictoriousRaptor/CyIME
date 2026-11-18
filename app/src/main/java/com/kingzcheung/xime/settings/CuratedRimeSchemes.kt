package com.kingzcheung.xime.settings

/**
 * Audited Rime data packages use Rime components, not Xime's application API.
 * Keep their exact source/hash here: CyIME's release number cannot satisfy an
 * unrelated Xime 2.x requirement, and must not pretend to support every such package.
 */
internal object CuratedRimeSchemes {
    private val wubi86 = MarketScheme(
        id = "wubi86",
        name = "五笔86（Rime 官方）",
        author = "Rime / 王永民",
        description = "Rime 官方五笔86，支持拼音反查；仅下载本方案和词库，基础依赖已随 CyIME 提供。",
        type = "remote",
        tags = listOf("五笔", "形码", "拼音"),
        dependencies = listOf("pinyin_simp", "symbols"),
        homepage = "https://github.com/rime/rime-wubi",
        license = "LGPL-3.0",
        currentVersion = "152a0d3",
        versions = listOf(SchemeVersion(
            version = "152a0d3",
            changelog = "固定 Rime 官方提交；仅下载五笔86方案和词库，不带其他方案或应用设置。",
            downloadUrls = listOf(
                DownloadItem(
                    url = "https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.schema.yaml",
                    sha256 = "cdb5aac1a9aa071552d5fdffdfe5a6618b429b19358b8b1e003130e835d5a166",
                    size = "0.0015 MB",
                ),
                DownloadItem(
                    url = "https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.dict.yaml",
                    sha256 = "f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a",
                    size = "2.18 MB",
                ),
            ),
        )),
    )
    private val wubi98 = MarketScheme(
        id = "wubi98",
        name = "五笔98",
        author = "Rime / 王永民",
        description = "五笔98字形方案，支持繁体字；独立方案和词库。",
        type = "remote",
        tags = listOf("五笔", "形码", "繁体"),
        dependencies = listOf("pinyin_simp", "symbols"),
        homepage = "https://github.com/cz-archive/rime-wubi98",
        license = "GPL-3.0",
        currentVersion = "1.0.0",
        versions = listOf(SchemeVersion(
            version = "1.0.0",
            downloadUrls = listOf(DownloadItem(
                url = "https://github.com/cz-archive/rime-wubi98/archive/refs/tags/1.0.0.tar.gz",
                sha256 = "7d105524b81373c7fef0cbfd197fa3c236bcb4264b1935c5e384bacb10fece53",
                size = "0.88 MB",
            )),
        )),
    )

    /**
     * 小鹤音形整包：方案、拼字和反查词库在仓库根目录和 flypy/ 子目录，lua/ 是日期、时间、计算器。
     * 装上后是外部编码方案（[InputScheme.EXTERNAL]），拼音的纠错、模糊音、滑行、排序都不碰它。
     */
    private val flypy = MarketScheme(
        id = "flypy",
        name = "小鹤音形",
        author = "何海峰 flypy.cc / cubercsl 整理",
        description = "小鹤音形：音码加形码，四码定长、满码顶字上屏；ok 引导拼字，带小鹤双拼反查。整套方案和词库一起下载。",
        type = "remote",
        tags = listOf("音形", "形码", "小鹤"),
        homepage = "https://github.com/cubercsl/rime-flypy",
        currentVersion = "9ee4647",
        versions = listOf(SchemeVersion(
            version = "9ee4647",
            changelog = "固定 cubercsl/rime-flypy 提交 9ee4647（码表 10.26.1）。",
            downloadUrls = listOf(DownloadItem(
                url = "https://github.com/cubercsl/rime-flypy/archive/9ee464765e325dfd4b04926028d79d60882e653e.tar.gz",
                sha256 = FLYPY_ARCHIVE_SHA256,
                size = FLYPY_ARCHIVE_SIZE,
            )),
        )),
    )

    /** The reviewed entries are visible first, even when the remote index is unavailable. */
    fun merge(remote: List<MarketScheme>): List<MarketScheme> =
        (listOf(wubi86, wubi98, flypy) + remote).distinctBy { it.id }

    /** Choose the advertised scheme, not a newly discovered mixed-input variant. */
    fun preferredSchema(packageId: String, availableSchemaIds: Set<String>): String? =
        packageId.takeIf { it in setOf(wubi86.id, wubi98.id, flypy.id) && it in availableSchemaIds }
}

// 电脑上填：curl -L <上面的 url> -o flypy.tar.gz && sha256sum flypy.tar.gz && 文件大小（MB，两位小数）。
// 云端拿不到 github.com 的归档，没填之前 CuratedRimeSchemesTest 会失败，提醒别漏。
private const val FLYPY_ARCHIVE_SHA256 = "8ff4f533ddbf84f90547c477fb6a21a441514bb1b91921786c646c9df2e51bbd"
private const val FLYPY_ARCHIVE_SIZE = "2.33 MB"
