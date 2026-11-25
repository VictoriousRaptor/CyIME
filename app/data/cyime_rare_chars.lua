--[[
  CyIME：中文 26 键全拼、双拼、14 键和九键的生僻字（开关 cyime_rare_chars，默认关）。
  主词库只有 8105 字表，瀺 这类字打不出来。打一个完整拼音音节（chan）时，在主翻译器的整段候选之后补上
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
  不进主词库：九键回放里整表导入让每键查词量翻了一倍多。开关关着时不读文件、不做任何事。
  学习（11-24，他：默认放后面靠降权重，选多了照样按学习往前调，不许用规则把它们排除在学习之外）：这里给的候选只是
  默认的低权重（排在主词库同音字后面）；选中后按读音记进这个方案的用户词库，和别的词一样，主翻译器从用户词库按学习
  权重给出它（选得多就往前，长按删除忘掉记录、固定照常）。原来的做法是另记一个小文件、按次数给固定分（1 次 2.2005、
  2 次 1000），九键上屏不走 Rime 的提交，一次也没记过。旧文件里的次数在这个字下次出现时搬进用户词库，搬完删掉那一行。
  九键的选字不经过这里的提交：九键按候选注释里的读音记（t9_processor），所以九键的候选注释都写读音。
  日文汉字、韩文汉字、粤语字（11-20，他：是什么就叫什么）：同样的查法，各自一张表、一个开关（默认关），候选注释
  加「日」「韩」「粤」（九键只写读音，右选和拂动筛选按注释里的拼音对）。日文汉字表里还有日本人名、地名
  （榎本武扬 jia ben wu yang），只在 26 键全拼和九键打满整个词时出。
  常用字的少见读音（谜 mei、那 nei，8105 字表没收、大字表有的，11-20 他问开了开关还缺不缺字）也跟生僻字开关，
  排在这个读音的生僻字前面；这些字本来就常用，上屏时不记次数（不然打一次 谜 mi，mei 下面的 谜 就提前了）。
  注音（11-20）同 14 键、双拼：按注音方案的拼写规则把读音算成大千键码再查。
--]]
-- 排在同音字后面的先后：中文生僻字、日文汉字、韩文汉字、粤语字（选过的字从用户词库按学习权重出，不在这里排）。
local LISTS = {
  { option = "cyime_rare_chars", file = "/cyime_rare.txt", mark = "", readings = "/cyime_rare_readings.txt" },
  { option = "cyime_japanese_kanji", file = "/cyime_japanese.txt", mark = "日" },
  { option = "cyime_korean_hanja", file = "/cyime_korean.txt", mark = "韩" },
  { option = "cyime_cantonese_chars", file = "/cyime_cantonese.txt", mark = "粤" },
}
local CANTONESE = LISTS[4]

-- 粤语常用字（GitHub #72）：开着粤语字时默认权重排在主词库所有同音字之后、生僻字之前（11-20 起跟「粤语字」开关）。
-- 冇 在主词库里读 mao，这里补上粤语读音 mou。只在粤语字开着时出现；选过以后从用户词库按学习权重出。
local COMMON = { qu = "佢", ge = "嘅", zuo = "咗", mou = "冇" }
local COMMON_QUALITY = -999
local migrate  -- defined below: moves an old pick count of the text into the user dictionary
local function with_mark(comment, mark)
  if mark == "" then return comment end
  if comment == "" then return mark end
  return comment .. " " .. mark
end

-- env.rare_readings：这一次给出的候选 字 → 读音（空格分开的音节），上屏时按它记进用户词库（见 remember_commit）。
local function yield_common(env, syllable, seg, comment, ctx)
  local text = COMMON[syllable]
  if not text or not ctx:get_option(CANTONESE.option) then return nil end
  migrate(env, text, syllable)
  env.rare_readings[text] = syllable
  local cand = Candidate("rare", seg.start, seg._end, text,
    env.rare_nine_key and syllable or with_mark(comment, CANTONESE.mark))
  cand.quality = COMMON_QUALITY
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
local function yield_extra(env, input, seg, nine_key)
  local typed = input:lower()
  for syllable, text in pairs(EXTRA) do
    local keys = syllable:gsub("%l", function(c) local i = c:byte() - 96; return KEYPAD:sub(i, i) end)
    if typed == syllable or input == keys then
      env.rare_readings[text] = syllable
      local cand = Candidate("rare", seg.start, seg._end, text, (nine_key or input == keys) and syllable or "")
      cand.quality = nine_key and EXTRA_T9_QUALITY or EXTRA_QUALITY
      yield(cand)
    end
  end
end

-- 旧版本记的次数（cyime_rare_learned.txt，一行「字\t次数」）：这个字下次出现、知道读音时搬进用户词库，搬完删掉那一行。
local LEARNED_FILE = "/cyime_rare_learned.txt"
local legacy  -- 字 → 次数，还没搬的
local function load_legacy()
  if legacy then return legacy end
  legacy = {}
  local dir = rime_api.get_user_data_dir()
  local f = dir and io.open(dir .. LEARNED_FILE, "r")
  if not f then return legacy end
  for line in f:lines() do
    local text, count = line:match("^([^\t]+)\t(%d+)$")
    if text then legacy[text] = tonumber(count) end
  end
  f:close()
  return legacy
end

local function save_legacy()
  local dir = rime_api.get_user_data_dir()
  if not dir or not legacy then return end
  local path = dir .. LEARNED_FILE
  if next(legacy) == nil then os.remove(path); return end
  local tmp = path .. ".tmp"
  local f = io.open(tmp, "w")
  if not f then return end
  for text, count in pairs(legacy) do f:write(text, "\t", count, "\n") end
  f:close()
  if not os.rename(tmp, path) then
    os.remove(path)
    os.rename(tmp, path)
  end
end

-- 记进这个方案主翻译器的用户词库（同一个库，排序、长按删除、固定都照常）。每次现开一个 Memory、用完就断开：
-- 一直开着的 Memory 会在每次上屏时自己开一个事务，把主翻译器还没提交的学习提前提交，退格撤销学习就失效了。
-- picks：{ { 字, 读音, 次数 }, ... }。
local function learn(env, picks)
  if #picks == 0 then return end
  local ok, memory = pcall(Memory, env.engine, env.engine.schema)
  if not ok or not memory then return end
  if memory.user_dict then
    for _, pick in ipairs(picks) do
      local entry = DictEntry()
      entry.text = pick[1]
      entry.custom_code = pick[2] .. " "
      memory:update_userdict(entry, pick[3], "")
    end
  end
  memory:disconnect()
end

function migrate(env, text, reading)
  local count = load_legacy()[text]
  if not count or env.engine.context:get_option("_cyime_incognito") then return end
  legacy[text] = nil
  if count > 0 then learn(env, { { text, reading, count } }) end
  save_legacy()
end

-- 上屏时选中的生僻字候选按读音各记一次（组词时先选常用字再选生僻字也算）。主词库的词由主翻译器自己记。
local function remember_commit(env, ctx)
  if ctx:get_option("_cyime_incognito") or not env.rare_readings then return end
  local picks = {}
  for _, seg in ipairs(ctx.composition:toSegmentation():get_segments()) do
    local cand = seg:get_selected_candidate()
    local genuine = cand and cand:get_genuine()
    local reading = genuine and genuine.type == "rare" and env.rare_readings[genuine.text]
    if reading then picks[#picks + 1] = { genuine.text, reading, 1 } end
  end
  learn(env, picks)
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
-- list.chars: syllable → the characters of that reading, concatenated (list.readings first)
-- list.counted: the characters whose commits are counted (not the common ones of list.readings), one string
-- list.words: whole code without spaces → "词\n" rows (日文汉字的人名、地名；只有 26 键全拼和九键查)
-- list.digits: nine-key digits → "字\tsyllable\n" rows in file order (words: "词\tjia ben wu yang\n")
local loaded = 0  -- lists loaded so far
local function read_rows(path, each)
  local f = io.open(rime_api.get_shared_data_dir() .. path, "r") or io.open(rime_api.get_user_data_dir() .. path, "r")
  if not f then return end
  for line in f:lines() do
    local text, code = line:match("^([^\t]+)\t(%l[%l ]*)$")
    if text then each(text, code) end
  end
  f:close()
end

local function load_chars(list)
  if list.chars then return list.chars end
  local chars, words, digits = {}, {}, {}
  list.chars, list.words, list.digits, list.merged = chars, words, digits, {}
  loaded = loaded + 1
  local by_code, by_word, by_keys, counted = {}, {}, {}, {}
  local function add(text, code, count)
    local joined = code:gsub(" ", "")
    local into = joined == code and by_code or by_word
    local same = into[joined]
    if not same then same = {}; into[joined] = same end
    same[#same + 1] = joined == code and text or text .. "\n"
    if count and joined == code then counted[#counted + 1] = text end
    local keys = joined:gsub("%l", function(c) local i = c:byte() - 96; return KEYPAD:sub(i, i) end)
    local rows = by_keys[keys]
    if not rows then rows = {}; by_keys[keys] = rows end
    rows[#rows + 1] = text .. "\t" .. code .. "\n"
  end
  if list.readings then read_rows(list.readings, function(text, code) add(text, code, false) end) end
  read_rows(list.file, function(text, code) add(text, code, true) end)
  for code, same in pairs(by_code) do chars[code] = table.concat(same) end
  for code, same in pairs(by_word) do words[code] = table.concat(same) end
  for keys, rows in pairs(by_keys) do digits[keys] = table.concat(rows) end
  list.counted = table.concat(counted)
  return chars
end

-- 开着的表（读进来），按 LISTS 的先后。
local function lists_on(ctx)
  local on = {}
  for _, list in ipairs(LISTS) do
    if ctx:get_option(list.option) then load_chars(list); on[#on + 1] = list end
  end
  return on
end

-- 14 键和双拼：每个方案按自己的拼写规则算一次「精确字母编码 → 读音」，同一个 Lua 环境里按表、按方案缓存（list.merged）。

-- 一条拼写规则拆成 种类、各段；分隔符是种类名后面那个字符（同 Rime）。最后不以分隔符结尾的是带标签的规则。
local function rule_parts(rule)
  local kind = rule:match("^(%l+)")
  if not kind or #rule <= #kind then return nil end
  local sep = "%" .. rule:sub(#kind + 1, #kind + 1)
  local parts = {}
  for part in rule:sub(#kind + 2):gmatch("([^" .. sep .. "]*)" .. sep) do parts[#parts + 1] = part end
  return kind, parts, not rule:find(sep .. "$")
end

local function merged_map(env, list)
  local schema = env.engine.schema
  local chars = load_chars(list)
  local map = list.merged[schema.schema_id]
  if map then return map end
  map = { codes = {}, keys = {}, longest = 0 }  -- codes: exact-letter code → "ai si"
  list.merged[schema.schema_id] = map
  local forms = {}  -- { code, syllable }
  for syllable in pairs(chars) do forms[#forms + 1] = { syllable, syllable } end
  -- 粤语字的常用字（佢 qu、冇 mou）不在表里也要能查到读音。
  if list == CANTONESE then
    for syllable in pairs(COMMON) do
      if not chars[syllable] then forms[#forms + 1] = { syllable, syllable } end
    end
  end
  local algebra = schema.config:get_list("speller/algebra")
  for i = 0, (algebra and algebra.size or 0) - 1 do
    local rule = algebra:get_value_at(i).value
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
    -- 分号：微软、搜狗双拼把 ing 放在分号键上。注音的大千键码还有数字和 , . / - 键（声调是 3 4 6 7）；
    -- 第一声是空格键，打出来的空格不算读音（reading 去掉了），只认不带声调和二到五声的码。
    if not code:find("[%s'%u]") then
      map.codes[code] = map.codes[code] and (map.codes[code] .. " " .. syllable) or syllable
      if #code > map.longest then map.longest = #code end
    end
  end
  return map
end

-- 输入（合并键大写、侧滑选的字母小写）能对上的读音，按键上字母的顺序。
local function merged_syllables(map, input)
  if #input > map.longest or input:find("[%s']") then return {} end
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

-- 一个读音的生僻字按文件顺序出，都是默认的低权重；选过的从用户词库出（主翻译器按学习权重排），这里再出一次的
-- 由去重滤掉。each(fn) 把这个读音的每个字、注释和读音交给 fn；仍是边出边建候选，不一次建几百个。
local function yield_rare(env, each, start, _end, skip)
  each(function(text, comment, reading)
    if text == skip then return end
    if reading then
      migrate(env, text, reading)
      if not env.rare_readings[text] then env.rare_readings[text] = reading end
    end
    local cand = Candidate("rare", start, _end, text, comment)
    cand.quality = -1000
    yield(cand)
  end)
end

-- 14 键、双拼：reading 能对上的读音各补一遍，开着的几张表一起（commons 是已经出过的粤语字）。没有对上的读音返回 false。
local function merged_yield(env, on, ctx, reading_text, start, _end)
  local found, any = {}, false
  for _, list in ipairs(on) do
    found[list] = merged_syllables(merged_map(env, list), reading_text)
    if #found[list] > 0 then any = true end
  end
  if not any then return false end
  local commons = {}
  local yue = found[CANTONESE] or {}
  for _, syllable in ipairs(yue) do
    local common = yield_common(env, syllable, { start = start, _end = _end }, #yue > 1 and syllable or "", ctx)
    if common then commons[common] = true end
  end
  yield_rare(env, function(fn)
    for _, list in ipairs(on) do
      local syllables = found[list]
      for _, syllable in ipairs(syllables) do
        local comment = with_mark(#syllables > 1 and syllable or "", list.mark)
        for text in (list.chars[syllable] or ""):gmatch(utf8.charpattern) do
          if not commons[text] then fn(text, comment, syllable) end
        end
      end
    end
  end, start, _end)
  return true
end

-- 全拼 26 键、九键：reading 正好是一个读音（chan，或九键 242）或日文汉字表里一个整词时补；对不上返回 false。
local function plain_yield(env, on, ctx, reading_text, start, _end)
  local key = reading_text:lower()
  local whole = (key:gsub("[' ]", ""))
  local common = yield_common(env, key, { start = start, _end = _end }, "", ctx)
  local hit = common ~= nil
  for _, list in ipairs(on) do
    if list.chars[key] or list.words[whole] then hit = true end
  end
  if hit then
    -- 九键（左栏选定的拼音）注释写读音：九键按注释里的读音记学习。
    yield_rare(env, function(fn)
      for _, list in ipairs(on) do
        for text in (list.chars[key] or ""):gmatch(utf8.charpattern) do
          fn(text, env.rare_nine_key and key or list.mark, key)
        end
        for text in (list.words[whole] or ""):gmatch("([^\n]+)\n") do fn(text, list.mark) end
      end
    end, start, _end, common)
    return true
  end
  -- 九键数字：注释只写读音、不加「日」这些标记。
  for _, list in ipairs(on) do
    if list.digits[reading_text] then hit = true end
  end
  if not hit then return false end
  yield_rare(env, function(fn)
    for _, list in ipairs(on) do
      for text, code in (list.digits[reading_text] or ""):gmatch("([^\t]+)\t([%l ]+)\n") do fn(text, code, code) end
    end
  end, start, _end)
  return true
end

-- 组词时（chanchan、chan'chan、九键 242626）整段不是一个读音：开头那个读音的生僻字照样补，只占开头那几个键，
-- 和主词库的单字候选一样选了再接着选后面的（#194、#477：第一个 chan 没有生僻字，第二个才有）。
-- 有分词符时按分词符前那一节；没有时取最长的、能对上读音的开头（最多 6 个键）。
local function leading_yield(input, offset, start, try)
  local first = input:match("^([^' ]+)[' ]")
  if first then return try(first, start + offset, start + offset + #first) end
  for len = math.min(#input - 1, 6), 1, -1 do
    if try(input:sub(1, len), start + offset, start + offset + len) then return true end
  end
  return false
end

local function translator(input, seg, env)
  local typed = input
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
  env.rare_readings = {}
  if not env.rare_merged_keys then yield_extra(env, input, seg, env.rare_nine_key) end
  local ctx = env.engine.context
  local on = lists_on(ctx)
  if #on == 0 then return end
  local try
  if env.rare_merged_keys then
    try = function(text, start, _end) return merged_yield(env, on, ctx, text, start, _end) end
  else
    try = function(text, start, _end) return plain_yield(env, on, ctx, text, start, _end) end
  end
  if try(input, seg.start, seg._end) then return end
  local offset = #typed - #typed:gsub("^[' ]+", "")
  leading_yield(input, offset, seg.start, try)
end

local function init(env)
  env.rare_commit = env.engine.context.commit_notifier:connect(function(ctx) remember_commit(env, ctx) end)
end

local function fini(env)
  if env.rare_commit then env.rare_commit:disconnect() end
end

return { init = init, func = translator, fini = fini }
