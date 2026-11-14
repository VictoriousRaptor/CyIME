package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.InputModes

/**
 * 存着的方案不在引擎方案列表里时还算不算可选（GitHub #340）。
 * 手写方案照旧可选；编译产物还在的方案只在仍然启用时可选（刚启用、还没部署完的那一会儿）。
 * 以前只看编译产物：停用、删掉的方案 build 里的文件不删，键盘就一直用着它，
 * 而布局栏只列启用的方案，找不到当前方案就画不出来，重启也一样。
 */
internal fun selectableWithStored(
    stored: String,
    available: List<String>,
    enabled: () -> Collection<String>,
    handwriting: (String) -> Boolean,
    compiled: (String) -> Boolean,
): List<String> =
    if (stored.isNotBlank() && stored !in available && stored != InputModes.ENGLISH &&
        (handwriting(stored) || compiled(stored) && stored in enabled())) available + stored else available
