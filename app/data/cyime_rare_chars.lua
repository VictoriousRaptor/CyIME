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
  不进主词库：九键回放里整表导入让每键查词量翻了一倍多。开关关着时不读文件、不做任何事；选中的字不进用户词库。
  日文汉字、韩文汉字、粤语字（11-20，他：是什么就叫什么）：同样的查法，各自一张表、一个开关（默认关），候选注释
  加「日」「韩」「粤」（九键只写读音，右选和拂动筛选按注释里的拼音对）。日文汉字表里还有日本人名、地名
  （榎本武扬 jia ben wu yang），只在 26 键全拼和九键打满整个词时出。
  常用字的少见读音（谜 mei、那 nei，8105 字表没收、大字表有的，11-20 他问开了开关还缺不缺字）也跟生僻字开关，
  排在这个读音的生僻字前面；这些字本来就常用，上屏时不记次数（不然打一次 谜 mi，mei 下面的 谜 就提前了）。
  注音（11-20）同 14 键、双拼：按注音方案的拼写规则把读音算成大千键码再查。
--]]
-- 排在同音字后面的先后：中文生僻字、日文汉字、韩文汉字、粤语字（选过的字照样提前，见 yield_rare）。
local LISTS = {
  { option = "cyime_rare_chars", file = "/cyime_rare.txt", mark = "", readings = "/cyime_rare_readings.txt" },
  { option = "cyime_japanese_kanji", file = "/cyime_japanese.txt", mark = "日" },
  { option = "cyime_korean_hanja", file = "/cyime_korean.txt", mark = "韩" },
  { option = "cyime_cantonese_chars", file = "/cyime_cantonese.txt", mark = "粤" },
}
local CANTONESE = LISTS[4]

-- 粤语常用字（GitHub #72）：开着粤语字时排在主词库所有同音字之后、生僻字之前，不沉到整表最后（11-20 起跟「粤语字」开关）。
-- 原来给了固定分 2.2005（本想排第 10 个左右），但同音字的分数随学习变化，有人小鹤双拼打 qu 时「佢」到了第 2
-- （2026-11-18），所以改成垫底。冇 在主词库里读 mao，这里补上粤语读音 mou。只在粤语字开着时出现，默认不变。
local COMMON = { qu = "佢", ge = "嘅", zuo = "咗", mou = "冇" }
local COMMON_QUALITY = -999
local rare_quality  -- defined below with the learned counts
local function with_mark(comment, mark)
  if mark == "" then return comment end
  if comment == "" then return mark end
  return comment .. " " .. mark
end

local function yield_common(syllable, seg, comment, ctx)
  local text = COMMON[syllable]
  if not text or not ctx:get_option(CANTONESE.option) then return nil end
  local cand = Candidate("rare", seg.start, seg._end, text, with_mark(comment, CANTONESE.mark))
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

-- 上屏时数一下这次的生僻字（见下面 count_rare_text）。
local count_rare_text
local function any_on(ctx)
  for _, list in ipairs(LISTS) do
    if ctx:get_option(list.option) then return true end
  end
  return false
end

local function remember_commit(ctx)
  if not any_on(ctx) or ctx:get_option("_cyime_incognito") then return end
  if count_rare_text(ctx:get_commit_text()) then save_learned() end
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
local loaded = 0  -- lists loaded so far (count_rare_text rebuilds its string when this grows)
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

-- 一个读音的生僻字按三遍出：选过两次起的、选过一次的、其余照文件顺序。Rime 合并几个翻译器时只比各自的下一个
-- 候选，照文件顺序出时选过的字分数再高也排在第一个没选过的字后面，打多少次都不动（#477）。
-- each(fn) 把这个读音的每个字和注释交给 fn；仍是边出边建候选，不一次建几百个。
local function yield_rare(each, start, _end, skip)
  local map = load_learned()
  for pass = 1, 3 do
    each(function(text, comment)
      if text == skip then return end
      local count = map[text] or 0
      if (pass == 1 and count >= 2) or (pass == 2 and count == 1) or (pass == 3 and count == 0) then
        local cand = Candidate("rare", start, _end, text, comment)
        cand.quality = rare_quality(text, -1000)
        yield(cand)
      end
    end)
  end
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
    local common = yield_common(syllable, { start = start, _end = _end }, #yue > 1 and syllable or "", ctx)
    if common then commons[common] = true end
  end
  yield_rare(function(fn)
    for _, list in ipairs(on) do
      local syllables = found[list]
      for _, syllable in ipairs(syllables) do
        local comment = with_mark(#syllables > 1 and syllable or "", list.mark)
        for text in (list.chars[syllable] or ""):gmatch(utf8.charpattern) do
          if not commons[text] then fn(text, comment) end
        end
      end
    end
  end, start, _end)
  return true
end

-- 全拼 26 键、九键：reading 正好是一个读音（chan，或九键 242）或日文汉字表里一个整词时补；对不上返回 false。
local function plain_yield(on, ctx, reading_text, start, _end)
  local key = reading_text:lower()
  local whole = (key:gsub("[' ]", ""))
  local common = yield_common(key, { start = start, _end = _end }, "", ctx)
  local hit = common ~= nil
  for _, list in ipairs(on) do
    if list.chars[key] or list.words[whole] then hit = true end
  end
  if hit then
    yield_rare(function(fn)
      for _, list in ipairs(on) do
        for text in (list.chars[key] or ""):gmatch(utf8.charpattern) do fn(text, list.mark) end
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
  yield_rare(function(fn)
    for _, list in ipairs(on) do
      for text, code in (list.digits[reading_text] or ""):gmatch("([^\t]+)\t([%l ]+)\n") do fn(text, code) end
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
  if not env.rare_merged_keys then yield_extra(input, seg, env.rare_nine_key) end
  local ctx = env.engine.context
  local on = lists_on(ctx)
  if #on == 0 then return end
  local try
  if env.rare_merged_keys then
    try = function(text, start, _end) return merged_yield(env, on, ctx, text, start, _end) end
  else
    try = function(text, start, _end) return plain_yield(on, ctx, text, start, _end) end
  end
  if try(input, seg.start, seg._end) then return end
  local offset = #typed - #typed:gsub("^[' ]+", "")
  leading_yield(input, offset, seg.start, try)
end

-- 上屏的文字里每个生僻字记一次（组词时先选常用字再选生僻字也算）。原来看每一节选中的候选是不是生僻字候选，
-- 九键的选字经过九键自己的处理，候选类型对不上，次数一直不涨（#477）。生僻字都不在主词库里，按字认就够了。
-- 日文汉字、韩文汉字、粤语字也一样（读进来的表都算）。
local chars_all  -- every character of the loaded lists in one string, rebuilt when another list is loaded
local chars_loaded = 0
function count_rare_text(text)
  if not chars_all or chars_loaded ~= loaded then
    chars_loaded = loaded
    local parts = {}
    for _, list in ipairs(LISTS) do parts[#parts + 1] = list.counted end
    for _, common in pairs(COMMON) do parts[#parts + 1] = common end
    chars_all = table.concat(parts)
  end
  local map, changed = load_learned(), false
  for ch in text:gmatch(utf8.charpattern) do
    if #ch > 1 and chars_all:find(ch, 1, true) then
      map[ch] = (map[ch] or 0) + 1
      changed = true
    end
  end
  return changed
end

local function init(env)
  env.rare_commit = env.engine.context.commit_notifier:connect(remember_commit)
end

local function fini(env)
  if env.rare_commit then env.rare_commit:disconnect() end
end

return { init = init, func = translator, fini = fini }
