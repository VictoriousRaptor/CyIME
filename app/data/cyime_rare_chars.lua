--[[
  CyIME：中文 26 键全拼、双拼、14 键和九键的生僻字（开关 cyime_rare_chars，默认关）。
  主词库只有 8105 字表，瀺、槻 这类字打不出来。打一个完整拼音音节（chan）时，在主翻译器的整段候选之后补上
  cyime_rare.txt 里这个读音的生僻字（构建时从雾凇大字表取 8105 没有的基本区和扩展 A 汉字，见 tasks-chinese.gradle.kts）。
  九键按一个音节的数字（484 = gui、hui）或左栏选定的拼音字母（gui）补；数字可能对应几个音节，按读音带上注释，
  拂动固定字母的筛选（t9_filter）照常按注释比对。
  双拼（GitHub #72，同一个开关）：方案带 cyime_rare_double_pinyin: true 时，和下面 14 键一样按这个方案自己的
  拼写规则算出每个读音的双拼编码再查，打满两个键（qu）补这个读音的字。原来按编码显示规则（preedit_format）换回
  拼音，显示规则为空的方案（自建布局上的双拼）一个字也不补（2026-11-03）。
  14 键（全拼和双拼，2026-11-02）：方案带 cyime_rare_merged_keys: true 时，按这个方案自己的拼写规则
  （speller/algebra）把每个读音算成精确字母编码，合并键（大写，AU = as 键 + ui 键）逐位换成键上的字母再查，
  对得上几个读音就补几个读音的字（AU 补 ai、si、su，带读音注释）；侧滑选定的字母（小写）只对自己。
  简拼、模糊音、纠错和手动分词的规则不算：只在打满一个完整读音时补。
  不进主词库：九键回放里整表导入让每键查词量翻了一倍多。开关关着时不读文件、不做任何事；选中的字不进用户词库。
--]]
local OPTION = "cyime_rare_chars"

-- 粤语常用字（GitHub #72）：开着生僻字时排在主词库所有同音字之后、其他生僻字之前，不沉到整表最后。
-- 原来给了固定分 2.2005（本想排第 10 个左右），但同音字的分数随学习变化，有人小鹤双拼打 qu 时「佢」到了第 2
-- （2026-11-18），所以改成垫底。冇 在主词库里读 mao，这里补上粤语读音 mou。只在这个开关开着时出现，默认不变。
local COMMON = { qu = "佢", ge = "嘅", zuo = "咗", mou = "冇" }
local COMMON_QUALITY = -999
local rare_quality  -- defined below with the learned counts
local function yield_common(syllable, seg, comment)
  local text = COMMON[syllable]
  if not text then return nil end
  local cand = Candidate("rare", seg.start, seg._end, text, comment)
  cand.quality = rare_quality(text, COMMON_QUALITY)
  yield(cand)
  return text
end

local KEYPAD = "22233344455566677778889999"

-- 常用字少收的读音（不看开关，2026-11-17 B 站评论：打 mo 找不到「么」）：只在整段输入正好是这个读音时
-- （26 键 mo，九键 66 或左栏选 mo）补进单字候选，排在主词库同音字之后。不进主词库：整句按字打分时
-- 「么」作语气词分数很高，加了 mo 读音后九键回放里句尾冒出「么」（我昨天没 → 我昨天么，14 处）。
-- 双拼、14 键不补（同一串字母在那里是别的读音）。
local EXTRA = { mo = "么" }
-- 26 键整段候选的质量在 2 上下（2.2005 排在 mo 的第 16 个左右）；九键的在 1.000x，取同样的位置（沫 之后）。
local EXTRA_QUALITY = 2.2005
local EXTRA_T9_QUALITY = 1.0005
local function yield_extra(input, seg, nine_key)
  local typed = input:lower()
  for syllable, text in pairs(EXTRA) do
    local keys = syllable:gsub("%l", function(c) local i = c:byte() - 96; return KEYPAD:sub(i, i) end)
    if typed == syllable or input == keys then
      local cand = Candidate("rare", seg.start, seg._end, text, input == keys and syllable or "")
      cand.quality = nine_key and EXTRA_T9_QUALITY or EXTRA_QUALITY
      yield(cand)
    end
  end
end

-- 用过的生僻字往前排（11-16 用户反馈：名字里的生僻字打多少次都上不了首位）。主词库的动态词频管不到这些字
-- （它们不进用户词库，见文件头），这里自己记：选过一次排到第一、二页，选过两次起排第一。
-- 记在用户目录的 cyime_rare_learned.txt（一行「字\t次数」），只在开关开着时读写；无痕模式不记。
local LEARNED_FILE = "/cyime_rare_learned.txt"
local LEARNED_MAX = 2000
local learned  -- 字 → 选过几次
local function load_learned()
  if learned then return learned end
  learned = {}
  local dir = rime_api.get_user_data_dir()
  local f = dir and io.open(dir .. LEARNED_FILE, "r")
  if not f then return learned end
  for line in f:lines() do
    local text, count = line:match("^([^\t]+)\t(%d+)$")
    if text then learned[text] = tonumber(count) end
  end
  f:close()
  return learned
end

local function save_learned()
  local dir = rime_api.get_user_data_dir()
  if not dir or not learned then return end
  local rows = {}
  for text, count in pairs(learned) do rows[#rows + 1] = { text, count } end
  table.sort(rows, function(a, b) return a[2] > b[2] end)
  local tmp = dir .. LEARNED_FILE .. ".tmp"
  local f = io.open(tmp, "w")
  if not f then return end
  for i = 1, math.min(#rows, LEARNED_MAX) do f:write(rows[i][1], "\t", rows[i][2], "\n") end
  f:close()
  if not os.rename(tmp, dir .. LEARNED_FILE) then
    os.remove(dir .. LEARNED_FILE)
    os.rename(tmp, dir .. LEARNED_FILE)
  end
end

-- 选过一次进前两页（主词库同音字之后约第 10–16 个）。
local LEARNED_ONCE_QUALITY = 2.2005
local LEARNED_QUALITY = 1000

function rare_quality(text, default)
  local count = load_learned()[text]
  if not count then return default end
  return count >= 2 and LEARNED_QUALITY or math.max(default, LEARNED_ONCE_QUALITY)
end

-- 上屏时数一下这次选中的生僻字（整段里每一节各自选的候选，组词时先选常用字再选生僻字也算）。
local function remember_commit(ctx)
  if not ctx:get_option(OPTION) or ctx:get_option("_cyime_incognito") then return end
  local segments = ctx.composition:toSegmentation():get_segments()
  local changed = false
  for _, seg in ipairs(segments) do
    local cand = seg:get_selected_candidate()
    local genuine = cand and (cand:get_genuine() or cand)
    if genuine and genuine.type == "rare" then
      local map = load_learned()
      map[genuine.text] = (map[genuine.text] or 0) + 1
      changed = true
    end
  end
  if changed then save_learned() end
end

-- 分词符（' 和空格）不算读音：组词时先选了前面的字，剩下那一节从分词符开始（aaa'bbb 选完 aaa 剩 'bbb），
-- 原来整段对不上读音，生僻字一个也不补（11-16 用户反馈）。
local function reading(input)
  return (input:gsub("^[' ]+", ""):gsub("[' ]+$", ""))
end

-- Loaded once per Lua state, only after the option is first on. Each list is one string, not a table per character:
-- librime-lua runs a full garbage collection every 256 Lua translations, and with 2 万 small tables each one stalled
-- a key for about 3 ms on the PC host engine (16–21 times in 4,000 keys), also after the switch was turned off
-- again. As strings no key took over 0.6 ms (2026-11-02).
local chars  -- syllable → the characters of that reading, concatenated
local digits  -- nine-key digits → "字\tsyllable\n" rows in file order
local function load_chars()
  if chars then return chars end
  chars, digits = {}, {}
  local name = "/cyime_rare.txt"
  local f = io.open(rime_api.get_shared_data_dir() .. name, "r") or io.open(rime_api.get_user_data_dir() .. name, "r")
  if not f then return chars end
  local by_code, by_keys = {}, {}
  for line in f:lines() do
    local text, code = line:match("^([^\t]+)\t(%l+)$")
    if text then
      local list = by_code[code]
      if not list then list = {}; by_code[code] = list end
      list[#list + 1] = text
      local keys = code:gsub("%l", function(c) local i = c:byte() - 96; return KEYPAD:sub(i, i) end)
      local rows = by_keys[keys]
      if not rows then rows = {}; by_keys[keys] = rows end
      rows[#rows + 1] = text .. "\t" .. code .. "\n"
    end
  end
  f:close()
  for code, list in pairs(by_code) do chars[code] = table.concat(list) end
  for keys, rows in pairs(by_keys) do digits[keys] = table.concat(rows) end
  return chars
end

-- 14 键和双拼：每个方案按自己的拼写规则算一次「精确字母编码 → 读音」，同一个 Lua 环境里按方案缓存。
local merged_maps = {}

-- 一条拼写规则拆成 种类、各段；分隔符是种类名后面那个字符（同 Rime）。最后不以分隔符结尾的是带标签的规则。
local function rule_parts(rule)
  local kind = rule:match("^(%l+)")
  if not kind or #rule <= #kind then return nil end
  local sep = "%" .. rule:sub(#kind + 1, #kind + 1)
  local parts = {}
  for part in rule:sub(#kind + 2):gmatch("([^" .. sep .. "]*)" .. sep) do parts[#parts + 1] = part end
  return kind, parts, not rule:find(sep .. "$")
end

local function merged_map(env)
  local schema = env.engine.schema
  local map = merged_maps[schema.schema_id]
  if map then return map end
  map = { codes = {}, keys = {}, longest = 0 }  -- codes: exact-letter code → "ai si"
  merged_maps[schema.schema_id] = map
  local forms = {}  -- { code, syllable }
  for syllable in pairs(load_chars()) do forms[#forms + 1] = { syllable, syllable } end
  local list = schema.config:get_list("speller/algebra")
  for i = 0, (list and list.size or 0) - 1 do
    local rule = list:get_value_at(i).value
    local kind, parts, tagged = rule_parts(rule)
    local merged_key = kind == "derive" and parts[2] and parts[2]:match("%u")
    if merged_key then
      -- derive/^(.{1})[as](.*)$/$1A$2/：A 键是 a、s 两个字母。
      local letters = parts[1]:match("%[(%l+)%]")
      if letters then map.keys[merged_key] = letters end
    elseif (kind == "xform" or kind == "xlit" or kind == "erase" or (kind == "derive" and not tagged))
        and not rule:find("'", 1, true) then
      local one = ConfigList()
      one:append(ConfigValue(rule).element)
      local projection = Projection()
      projection:load(one)
      local next_forms, seen = {}, {}
      local function keep(code, syllable)
        local id = code .. " " .. syllable
        if code ~= "" and not seen[id] then seen[id] = true; next_forms[#next_forms + 1] = { code, syllable } end
      end
      for _, form in ipairs(forms) do
        local result = projection:apply(form[1], true)
        if kind == "derive" then keep(form[1], form[2]) end
        keep(result, form[2])
      end
      forms = next_forms
    end
  end
  -- One string per code (syllables separated by spaces), not a table: see load_chars.
  for _, form in ipairs(forms) do
    local code, syllable = form[1], form[2]
    -- 分号：微软、搜狗双拼把 ing 放在分号键上。
    if code:match("^[%l;]+$") then
      map.codes[code] = map.codes[code] and (map.codes[code] .. " " .. syllable) or syllable
      if #code > map.longest then map.longest = #code end
    end
  end
  return map
end

-- 输入（合并键大写、侧滑选的字母小写）能对上的读音，按键上字母的顺序。
local function merged_syllables(map, input)
  if #input > map.longest or not input:match("^[%a;]+$") then return {} end
  local found, seen = {}, {}
  local function walk(i, prefix)
    if i > #input then
      for syllable in (map.codes[prefix] or ""):gmatch("%S+") do
        if not seen[syllable] then seen[syllable] = true; found[#found + 1] = syllable end
      end
      return
    end
    local key = input:sub(i, i)
    local letters = map.keys[key] or key
    for j = 1, #letters do walk(i + 1, prefix .. letters:sub(j, j)) end
  end
  walk(1, "")
  return found
end

local function merged_translate(input, seg, env)
  local syllables = merged_syllables(merged_map(env), reading(input))
  for _, syllable in ipairs(syllables) do
    local comment = #syllables > 1 and syllable or ""
    local common = yield_common(syllable, seg, comment)
    for text in (chars[syllable] or ""):gmatch(utf8.charpattern) do
      if text == common then goto next_char end
      local cand = Candidate("rare", seg.start, seg._end, text, comment)
      cand.quality = rare_quality(text, -1000)
      yield(cand)
      ::next_char::
    end
  end
end

local function translator(input, seg, env)
  input = reading(input)
  if input == "" then return end
  if env.rare_merged_keys == nil then
    local config = env.engine.schema.config
    env.rare_merged_keys = config:get_bool("cyime_rare_merged_keys") == true or
      config:get_bool("cyime_rare_double_pinyin") == true
    -- 九键方案带 t9_processor（rime_ice 也有 t9: 配置段，不能按它判断）。
    env.rare_nine_key = false
    local processors = config:get_list("engine/processors")
    for i = 0, (processors and processors.size or 0) - 1 do
      if processors:get_value_at(i).value == "t9_processor" then env.rare_nine_key = true end
    end
  end
  if not env.rare_merged_keys then yield_extra(input, seg, env.rare_nine_key) end
  if not env.engine.context:get_option(OPTION) then return end
  if env.rare_merged_keys then return merged_translate(input, seg, env) end
  local list = load_chars()[input:lower()]
  local common = yield_common(input:lower(), seg, "")
  if list or common then
    for text in (list or ""):gmatch(utf8.charpattern) do
      if text == common then goto next_char end
      local cand = Candidate("rare", seg.start, seg._end, text, "")
      -- Rime orders whole-input candidates by quality: below every dictionary word of this reading,
      -- unless the user has picked this character before (rare_quality).
      cand.quality = rare_quality(text, -1000)
      yield(cand)
      ::next_char::
    end
    return
  end
  for text, code in (digits[input] or ""):gmatch("([^\t]+)\t(%l+)\n") do
    local cand = Candidate("rare", seg.start, seg._end, text, code)
    cand.quality = rare_quality(text, -1000)
    yield(cand)
  end
end

local function init(env)
  env.rare_commit = env.engine.context.commit_notifier:connect(remember_commit)
end

local function fini(env)
  if env.rare_commit then env.rare_commit:disconnect() end
end

return { init = init, func = translator, fini = fini }
