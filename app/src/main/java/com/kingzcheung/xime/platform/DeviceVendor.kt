package com.kingzcheung.xime.platform

/**
 * 手机厂商，按「各家系统的做法一样」归类：同一类走同一条适配分支。
 *
 * 规则（`PlatformApiGuardTest` 会查）：只有 [AndroidPlatform] 读系统给的厂商名和品牌，
 * 别处用 `AndroidPlatform.vendor == DeviceVendor.SAMSUNG` 这样判断。新发现某家系统要单独处理，
 * 先在这里加一类（或给已有的类加厂商名），再在调用处用它。
 *
 * 要按系统版本分（ColorOS 11 和 12 不一样这种）时用 [AndroidPlatform.rom]（[OemRom]）。
 *
 * OPPO（ColorOS，一加、realme 同一套）和 vivo（OriginOS / Funtouch，含 iQOO）是适配的重灾区，
 * 同一件事安卓有标准做法，它们偏要另搞一套，还不给公开接口，我们只能挨个撞上、挨个绕：
 * - vivo 安装器不认只有 v2 签名的安装包（「安装包缺乏开发者证书」），别家都认，正式包只好补签 v1。
 * - vivo 手势导航时在键盘下面自己画一条「切换输入法」按钮，却不按标准报给输入法，键盘一贴底就把它压住。
 * - vivo OriginOS 6 的系统「选择输入法」框可能一个输入法都不列，只剩「取消」，引导只能改成去系统设置里选。
 * - vivo 会拦下输入法自己弹的 Toast，语音出错只能改在键盘上写一行提示。
 * - 手势导航开没开，安卓有标准的 navigation_mode；OPPO 用自己的 hide_navigationbar_enable，vivo 用
 *   navigation_gesture_on（小米、华为也各有一个），标准那项常年停在「三键」，只能一家家读私有开关。
 * - OPPO ColorOS 划掉设置页的卡片，会把整个应用连同正在用的输入法一起停掉，不让它自己回来（见 MainActivity）。
 * - 两家的「自启动」「后台耗电」页面没有公开入口，包名类名还隔几代就换（见 OemBackgroundGuide）。
 * - 验证码短信默认被系统「验证码安全保护」截住，第三方读不到，只能告诉用户去系统里关（小米、华为也这样）。
 */
enum class DeviceVendor(val label: String, private vararg val names: String) {
    SAMSUNG("三星", "samsung"),
    XIAOMI("小米", "xiaomi", "redmi", "poco"),
    VIVO("vivo", "vivo", "iqoo"),
    OPPO("OPPO", "oppo", "oneplus", "realme"),
    HUAWEI("华为", "huawei"),
    HONOR("荣耀", "honor"),
    OTHER("其他");

    companion object {
        /** 先看厂商名；厂商名不认识时再看品牌（个别机型厂商写代工厂、品牌才是真名）。 */
        fun of(manufacturer: String?, brand: String? = null): DeviceVendor =
            match(manufacturer) ?: match(brand) ?: OTHER

        private fun match(name: String?): DeviceVendor? {
            val key = name?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return null
            return entries.firstOrNull { key in it.names }
        }
    }
}
