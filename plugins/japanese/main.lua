-- 日语插件（Lua 脚本插件）
--
-- 日语方案、键盘、azooKey 转换都在宿主里（manifest capabilities.japanese）：
-- 本脚本只是开关，装上并启用后，宿主打开日语、下载日语词库和转换引擎；
-- 停用就把日语从语言切换里拿掉，卸载时删掉下载的词库（学习记录留着）。

local plugin = {}

-- 日语没有要脚本管的设置项；下载进度在插件页里由宿主画。
function plugin.getSettingsSchema()
  return {}
end

return plugin
