package com.kingzcheung.xime.service

/** 输入法服务在本进程里开着（系统只为启用的输入法开服务）；设置页判断「已启用」的证据之一（#508）。 */
internal object ImeServiceLiveness {
    @Volatile var alive = false
}
