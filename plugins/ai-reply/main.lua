-- AI 回复插件（Lua 脚本插件，同步全链路）
--
-- 用法：先复制对方的消息，再点键盘菜单里的「AI 回复」，点一条回复发到输入框。
--
-- 职责划分：
--   Lua   = prompt 模板组装 + 调用 LLM（host.http.request）+ 解析候选列表
--   宿主  = 全屏纯展示面板（InfoPanel：ui 节点树 + items 候选点选上屏）+ 通用原语：
--     host.http.request  同步 HTTP（第 5 参 timeoutMillis 覆盖长生成超时）
--     host.json          JSON 编解码
--     host.config        配置存储
--
-- 工具面板契约（host 调用，display: passive）：
--   getPanelState(inputText)  返回面板状态 { items, ui, loading }（入参为宿主收集的上下文）
--   onPanelInput(text)        输入变化（passive 无输入框，保留兼容）
--   onPanelAction(actionId)   ui 节点 action 点击（generate = 调用 LLM）
--   onPanelItemClick(itemId)  点候选（上屏由宿主完成）

local plugin = {}

local KEY_API_KEY = "apiKey"
local KEY_BASE_URL = "baseUrl"
local KEY_MODEL = "model"
local KEY_PROMPT = "prompt"

local DEFAULTS = {
  baseUrl = "https://api.openai.com/v1",
  model = "gpt-4o-mini",
  prompt = "你是我的智能回复助手。请根据对方消息生成 3~5 条简洁、自然、符合我语气的中文回复候选，每条不超过 30 字。严格按以下格式输出：只输出一个 JSON 对象，格式为 {\"candidates\": [\"好的呀\", \"马上来\", \"稍等片刻\"]}，candidates 是候选文本数组，不要编号、不要解释、不要任何其他文字。对方消息：{context}",
}

local lastContext = ""
local lastError = ""
local cachedItems = {}
local generating = false

-- ================= 工具函数 =================

local function trim(s)
  return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

local SPARK = "https://spark-api-open.xf-yun.com/x2"
local function usesSpark() return host.config.get("provider") == SPARK end
local function providerKey()
  return trim(host.config.get(usesSpark() and "sparkPassword" or KEY_API_KEY) or "")
end


-- 把 LLM 输出的多行文本拆成候选列表（去除编号/引号前缀，JSON 解析失败的兜底）
local function splitCandidates(text)
  local lines = {}
  for line in text:gmatch("[^\n]+") do
    local t = trim(line)
    t = t:gsub("^%s*[-%d%.%)%]%s]*", ""):gsub("^[\"']", ""):gsub("[\"']$", "")
    t = trim(t)
    if t ~= "" then lines[#lines + 1] = t end
  end
  return lines
end

-- 从模型输出中提取 JSON（剥离 ```json 代码块包裹，取首个 { 或 [ 到末尾对应括号的区间）
local function extractJson(text)
  local t = text:gsub("```[%w]*", ""):gsub("```", "")
  t = trim(t)
  local startIdx = t:find("[{%[]")
  if startIdx == nil then return nil end
  local endIdx = t:reverse():find("[}%]]")
  if endIdx == nil then return nil end
  local lastIdx = #t - endIdx + 1
  if lastIdx <= startIdx then return nil end
  return t:sub(startIdx, lastIdx)
end

-- 解析候选：优先 JSON 对象（prompt 已要求 {"candidates": [...]}），兼容直接数组，失败时按行拆分兜底
local function parseCandidates(content)
  local items = {}
  local json = extractJson(content or "")
  if json ~= nil then
    local data = host.json.decode(json)
    local list = data
    if type(data) == "table" and data.candidates ~= nil then
      list = data.candidates
    end
    if type(list) == "table" then
      for _, v in ipairs(list) do
        if type(v) == "string" then
          local t = trim(v)
          if t ~= "" then items[#items + 1] = { id = tostring(#items + 1), text = t } end
        end
      end
    end
    if #items > 0 then return items end
  end
  for _, line in ipairs(splitCandidates(content or "")) do
    items[#items + 1] = { id = tostring(#items + 1), text = line }
  end
  return items
end

-- ================= 配置 schema（与 manifest 一致，插件中心表单数据源） =================

function plugin.getSettingsSchema()
  -- 默认地址也写进配置：设置页点「保存」时宿主才会给配置里的域名联网授权
  if trim(host.config.get(KEY_BASE_URL) or "") == "" then host.config.set(KEY_BASE_URL, DEFAULTS.baseUrl) end
  if trim(host.config.get(KEY_MODEL) or "") == "" then host.config.set(KEY_MODEL, DEFAULTS.model) end
  local fields = {
    { type = "section", label = "接口" },
    { key = "provider", label = "服务商", type = "select", options = { "自定义兼容接口", SPARK },
      helpText = "讯飞星火 X2 对应 spark-api-open.xf-yun.com。",
      optionLabels = { [SPARK] = "讯飞星火 X2" }, defaultValue = "自定义兼容接口" },
    { key = "testConnection", label = "测试接口", type = "button" },
    { type = "section", label = "提示词（清空就恢复默认）" },
    {
      key = KEY_PROMPT,
      label = "提示词",
      type = "textarea",
      defaultValue = DEFAULTS.prompt,
      helpText = "{context} 换成对方的消息",
    },
  }
  -- 旧宿主显示全部服务的独立字段；新版按所选服务显示并校验。
  local providerFields = {
    { value = "自定义兼容接口", fields = {
      {
        key = KEY_API_KEY,
        label = "API 密钥",
        type = "secret",
        placeholder = "sk-…",
        requiredWhenVisible = true,
        helpText = "只存在这台手机里，不进备份，只发给下面的接口地址",
      },
      {
        key = KEY_BASE_URL,
        label = "接口地址",
        type = "text",
        defaultValue = DEFAULTS.baseUrl,
        helpText = "OpenAI 兼容接口，到 /v1 为止。例：https://api.deepseek.com/v1、https://api.siliconflow.cn/v1",
      },
      {
        key = KEY_MODEL,
        label = "模型",
        type = "text",
        defaultValue = DEFAULTS.model,
        helpText = "例：gpt-4o-mini、deepseek-chat、Qwen/Qwen2.5-7B-Instruct",
      },
    } },
    { value = SPARK, fields = {
      { key = "sparkPassword", label = "星火 X2 APIPassword", type = "secret", requiredWhenVisible = true, helpText = "选择讯飞星火 X2 时填写，使用星火控制台的 HTTP 凭据" },
    } },
  }
  local index = 3
  for _, group in ipairs(providerFields) do
    for _, field in ipairs(group.fields) do
      field.visibleWhen = { key = "provider", value = group.value }
      table.insert(fields, index, field)
      index = index + 1
    end
  end
  return fields
end

-- ================= 工具面板契约 =================

-- ui 节点树（统一 UiNode 白名单 type：section/text/metric/divider/button）：
--   未生成 → 对方消息预览 + "生成回复"按钮；失败 → 错误提示 + 重试按钮；
--   生成中 → 空 ui（loading 字段驱动宿主显示加载态）；有候选 → 提示文字（items 由宿主渲染）
local function buildUi()
  local ui = {}
  -- 没填密钥：一打开就说，先去设置
  if providerKey() == "" then
    ui[#ui + 1] = { type = "section", label = "先填接口" }
    ui[#ui + 1] = { type = "text", value = "AI 回复用你自己的 AI 接口，给复制来的消息想几条回复。点「设置」填 API 密钥、接口地址和模型。", style = "caption" }
    ui[#ui + 1] = { type = "button", label = "设置", key = "host:settings" }
    return ui
  end
  if #cachedItems > 0 then
    ui[#ui + 1] = { type = "section", label = "回复" }
    ui[#ui + 1] = { type = "text", value = "点一条发到输入框", style = "caption" }
  elseif generating then
    -- loading 态：宿主显示"加载中..."
  else
    ui[#ui + 1] = { type = "section", label = "对方消息" }
    if lastContext ~= "" then
      ui[#ui + 1] = { type = "text", value = lastContext }
    else
      ui[#ui + 1] = { type = "text", value = "先复制对方的消息，再点 AI 回复。", style = "caption" }
    end
    if lastError ~= "" then
      ui[#ui + 1] = { type = "text", value = lastError, style = "caption" }
    end
    ui[#ui + 1] = { type = "button", label = lastError ~= "" and "重试" or "生成回复", key = "generate" }
  end
  -- 宿主保留的动作：打开这个插件的设置页
  ui[#ui + 1] = { type = "button", label = "设置", key = "host:settings" }
  return ui
end

function plugin.getPanelState(inputText)
  -- openToolPanel 时宿主传入收集的上下文（选区 > 输入框 > 剪贴板）；
  -- action 点击后的单次重拉传空串，不覆盖已有上下文。
  -- 上下文变化（用户复制了新消息）→ 旧候选/错误状态失效，回到初始态
  if inputText ~= nil and trim(inputText) ~= "" then
    local ctx = trim(inputText)
    if ctx ~= lastContext then
      cachedItems = {}
      lastError = ""
      generating = false
    end
    lastContext = ctx
  end
  return {
    items = cachedItems,
    loading = generating,
    ui = buildUi(),
  }
end

function plugin.onPanelInput(text)
  lastContext = text or ""
end

-- 服务器在错误回复里说的原因（OpenAI 格式 {"error":{"message":…}}，也认 {"message":…}、{"error":"…"}、
-- {"detail":"…"}，不是 JSON 就取第一行）。只取一行、最多 80 字；回复里万一带了密钥就换成 ***。
local MAX_SERVER_MESSAGE = 80

local function serverMessage(text, apiKey)
  text = tostring(text or "")
  local msg = nil
  local ok, data = pcall(host.json.decode, text)
  if ok and type(data) == "table" then
    local e = data.error
    if type(e) == "table" and type(e.message) == "string" then msg = e.message
    elseif type(e) == "string" then msg = e
    elseif type(data.message) == "string" then msg = data.message
    elseif type(data.detail) == "string" then msg = data.detail
    elseif type(data.msg) == "string" then msg = data.msg
    end
  elseif text:match("^%s*{") then
    -- 被截断、解析不了的 JSON：直接找 "message"
    msg = text:match('"message"%s*:%s*"([^"]*)')
  elseif not text:match("^%s*[%[<]") then
    msg = text  -- 纯文字回复；HTML 错误页不显示
  end
  if msg == nil then return "" end
  msg = (msg:match("^[^\r\n]*") or ""):gsub("^%s+", ""):gsub("%s+$", "")
  if apiKey ~= nil and #apiKey >= 6 then
    local s, e = msg:find(apiKey, 1, true)
    while s ~= nil do
      msg = msg:sub(1, s - 1) .. "***" .. msg:sub(e + 1)
      s, e = msg:find(apiKey, s + 3, true)
    end
  end
  -- 别家的 sk- 密钥也遮掉（task-1 这类不算）
  msg = msg:gsub("()(sk%-[A-Za-z0-9_%-]+)", function(pos, key)
    if pos > 1 and msg:sub(pos - 1, pos - 1):match("^[A-Za-z0-9_]$") then return key end
    return "sk-***"
  end)
  local out, n = {}, 0
  for ch in msg:gmatch("[%z\1-\127\194-\244][\128-\191]*") do
    n = n + 1
    if n > MAX_SERVER_MESSAGE then return table.concat(out) .. "…" end
    out[#out + 1] = ch
  end
  return msg
end

-- 有的模型只接受固定的 temperature（Kimi K2.5 以后的模型、OpenAI 推理模型等），带了就回 400。
-- 遇到这种 400 就不带 temperature 重发一次，并记住这个接口地址 + 模型，之后都不带。
local noTemperature = {}

local function rejectsTemperature(status, text)
  return status == 400 and tostring(text or ""):lower():find("temperature", 1, true) ~= nil
end

-- 调接口给 context 想几条回复。返回 items, err（err 非 nil 时 items 为 nil）
local function requestReplies(context)
  local apiKey = providerKey()
  if apiKey == "" then return nil, "还没填 API 密钥：点下面的「设置」" end

  local function configOr(key, default)
    local v = trim(host.config.get(key) or "")
    if v == "" then return default end
    return v
  end
  local baseUrl = usesSpark() and SPARK or configOr(KEY_BASE_URL, DEFAULTS.baseUrl):gsub("/+$", "")
  local model = usesSpark() and "spark-x" or configOr(KEY_MODEL, DEFAULTS.model)
  local prompt = configOr(KEY_PROMPT, DEFAULTS.prompt)
  prompt = prompt:gsub("{context}", function() return context end)

  local headers = {
    ["Content-Type"] = "application/json",
    ["Authorization"] = "Bearer " .. apiKey,
  }
  local url = baseUrl .. "/chat/completions"
  local endpoint = baseUrl .. "|" .. model
  local function send(withTemperature)
    local req = {
      model = model,
      messages = {
        { role = "system", content = "你是智能回复助手。严格按格式输出：只输出一个 JSON 对象，格式为 {\"candidates\": [\"回复一\", \"回复二\"]}，不要任何其他文字。" },
        { role = "user", content = prompt },
      },
    }
    if usesSpark() then req.thinking = { type = "disabled" } end
    if withTemperature then req.temperature = 0.8 end
    return host.http.request("POST", url, headers, host.json.encode(req), 60000)
  end

  local withTemperature = not noTemperature[endpoint]
  local resp = send(withTemperature)
  if resp ~= nil and withTemperature and rejectsTemperature(resp.status, resp.text) then
    noTemperature[endpoint] = true
    resp = send(false)
  end
  if resp == nil then
    local err = "连不上接口：" .. (host.http.lastError() or "未知错误")
    host.logError(err)
    return nil, err
  end
  if resp.status ~= 200 then
    -- 带上服务器原话，例：400: invalid temperature: only 1 is allowed for this model
    local detail = serverMessage(resp.text, apiKey)
    local err = "接口返回 " .. tostring(resp.status)
    if detail ~= "" then err = tostring(resp.status) .. ": " .. detail end
    host.logError(err)
    return nil, err
  end

  local data = host.json.decode(resp.text or "")
  local items = {}
  if type(data) == "table" and type(data.choices) == "table" then
    for _, choice in ipairs(data.choices) do
      local content = type(choice) == "table" and choice.message and choice.message.content or ""
      for _, item in ipairs(parseCandidates(content)) do
        items[#items + 1] = item
      end
    end
  end
  if #items == 0 then return nil, "接口返回的格式看不懂，再试一次" end
  return items, nil
end

function plugin.onPanelAction(actionId)
  if actionId ~= "generate" then return end
  local context = trim(lastContext)
  if context == "" then
    lastError = "先复制对方的消息，再点 AI 回复。"
    return
  end
  if generating then return end

  if providerKey() == "" then
    lastError = "还没填 API 密钥：点下面的「设置」"
    return
  end

  generating = true
  lastError = ""
  cachedItems = {}

  local items, err = requestReplies(context)
  if err ~= nil then
    lastError = err
    items = {}
  end
  cachedItems = items
  generating = false
end

-- 设置页「测试接口」：拿一句示例消息走一遍完整请求，能解析出回复才算通过。nil = 成功
function plugin.testConnection()
  if providerKey() == "" then return "还没填 API 密钥" end
  local _, err = requestReplies("明天上午十点开会，你能来吗？")
  return err
end

function plugin.onPanelItemClick(itemId)
  -- 点选上屏即本次任务完成：重置面板状态，下次打开回到初始态
  -- （否则旧候选残留，新复制的消息看起来"没效果"）
  cachedItems = {}
  lastError = ""
  generating = false
end

return plugin
