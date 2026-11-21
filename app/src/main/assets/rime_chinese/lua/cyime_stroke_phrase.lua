-- 笔画输入 · 内置分词打词模块（含短码补字 + 自动造词）
-- ============================================================
-- 本文件是「替换内置文件」的完整成品，直接覆盖
--   app/src/main/assets/rime_chinese/lua/cyime_stroke_phrase.lua
-- 不需要改 stroke.schema.yaml 的 translators，不需要用户改任何配置，
-- 不需要手动算 rime-bundled-manifest.tsv 的哈希（gradle 构建时自动生成）。
--
-- 相对 1.15.87 内置原版，本文件合并/修复了三件事：
--   1) 原样保留内置分词打词（词表整词 / 拼句 / 长词 / 分段词）；
--   2) 短码补字：按分词键后，符合首段编码的单字不再消失（全量按码表给，
--      并用 user_lookup / iter_user 让用户提过频的字排前面）；
--   3) 自动造词：选上屏的 2~8 字纯汉字词按每字首笔记住，下次分词打（每段笔画多少都行）出整词；
--      一个字一个字上屏、同样连着打过两次的两到四个字也记成词（11-19，见「三、自动造词」）。
--
-- 所有 librime-lua 扩展 API 都做了 pcall 降级，旧版 librime 最多没有
-- 补字/造词，内置单字、通配符、词表词组完全不受影响。
-- ============================================================

local M = {}

-- ===== 内置分词打词 =====
local MAX_WORD_PHRASE = 4
local MAX_SEGMENTS = 8
local SCAN_EXACT = 12000
local SCAN_LONGER = 1500
local MAX_EXACT = 30
local MAX_LONGER = 20
local MAX_HEADS = 10
local QUALITY = 100

-- ===== 造词 =====
local PREFIX = "="            -- 用户词条编码私有前缀，隔离 Rime 前缀查询
local MIN_WORD = 2
local MAX_WORD_LEARN = 8
local BASE_QUALITY = 95       -- 学过的词，排在词表词之后、补字之前
local CACHE_MAX = 200

-- ===== 补字 =====
local HEAD_QUALITY = 60

-- ===== 空段 =====
-- n 个分词号就是 n+1 个字（11-18 贡献者）：分词号之间、后面没打笔画的段代表任意一个字，
-- 首笔按横竖撇点折五种展开查词表，最多展开 125 种（三个空段），再多就不查，免得打字卡顿。
local STROKES = { "h", "s", "p", "n", "z" }
local MAX_FIRST_CODES = 125

-- 编码默认显示字母 h s p n z（11-18）；设置里选了显示笔画时，方案补丁写回 translator/preedit_format，
-- lua 造的候选跟着换成笔画（#372：不然分词后的编码一半字母一半笔画）。分词号、通配符原样留着。
local STROKE_GLYPHS = { h = "⼀", s = "⼁", p = "⼃", n = "⼂", z = "乛" }
local glyphs = false
local function stroke_preedit(code)
  if not glyphs then return code end
  return (code:gsub("[hspnz]", STROKE_GLYPHS))
end

-- 把编码按分词键切段。只允许 h/s/p/n/z 和分词键 '（含 * 的通配查询交给内置
-- wildcard 模块，这里整段不处理，避免重复给候选）。
-- n 个分词号切成 n+1 段，空段（分词号连着打、或打在最后）代表一个还没打笔画的字：
-- zzhpzns' 出骆驼，hshszh'nzsnnphhhs'' 出吉祥如意（11-18）。至少要有一段打了笔画。
local function split(input)
  if not input:find("'", 1, true) or input:find("[^hspnz']") then return nil end
  local parts = {}
  for part in (input .. "'"):gmatch("([^']*)'") do parts[#parts + 1] = part end
  if #parts < 2 or #parts > MAX_SEGMENTS then return nil end
  for _, part in ipairs(parts) do
    if part ~= "" then return parts end
  end
  return nil
end

local function has_empty(parts)
  for _, part in ipairs(parts) do
    if part == "" then return true end
  end
  return false
end

-- 只接受纯汉字（CJK 基本区，UTF-8 三字节），返回字数。不用 utf8.codes，
-- 部分 librime-lua 的 utf8 兼容库按字节迭代会误判汉字。
local function han_len(text)
  local n, i = 0, 1
  while i <= #text do
    local b = text:byte(i)
    if b >= 0xe4 and b <= 0xe9 and i + 2 <= #text then
      i = i + 3; n = n + 1
    else
      return nil
    end
  end
  return n
end

-- ============================================================
-- 一、内置分词打词（原 cyime_stroke_phrase.lua，逻辑原样保留）
-- ============================================================

local function codes_of(env, ch)
  local cached = env.codes[ch]
  if cached == nil then
    cached = {}
    local found = env.strokes:lookup(ch)
    if found then
      for code in found:gmatch("%S+") do cached[#cached + 1] = code end
    end
    env.codes[ch] = cached
  end
  return cached
end

local function starts_with(env, ch, part)
  for _, code in ipairs(codes_of(env, ch)) do
    if code:sub(1, #part) == part then return true end
  end
  return false
end

local function matches(env, text, parts)
  local i = 0
  for _, cp in utf8.codes(text) do
    i = i + 1
    if i > #parts then return true end
    if not starts_with(env, utf8.char(cp), parts[i]) then return false end
  end
  return i >= #parts
end

-- 词表按每个字的第一笔编码。空段的第一笔不知道，五种都查；展开太多返回 nil。
local function first_codes(parts, from, to)
  local codes = { "" }
  for i = from, to do
    local first = parts[i]:sub(1, 1)
    local options = first == "" and STROKES or { first }
    local expanded = {}
    for _, prefix in ipairs(codes) do
      for _, option in ipairs(options) do expanded[#expanded + 1] = prefix .. option end
    end
    if #expanded > MAX_FIRST_CODES then return nil end
    codes = expanded
  end
  return codes
end

-- 第 from 到 to 段对得上的词（每个字的笔顺开头都对得上），按词频从高到低，最多 limit 个。
-- 词表每个编码下的词本来就按词频排，所以每个编码只取前 limit 个对得上的；
-- 展开了几个编码时扫描上限按编码平分（每个至少 SCAN_PER_CODE），总量和以前一个编码时差不多。
local SCAN_PER_CODE = 500
local function lookup_words(env, parts, from, to, limit, seen)
  local codes = first_codes(parts, from, to)
  if not codes then return {} end
  local slice = {}
  for i = from, to do slice[#slice + 1] = parts[i] end
  local budget = #codes == 1 and SCAN_EXACT or math.max(SCAN_PER_CODE, math.floor(SCAN_EXACT / #codes))
  local found, taken = {}, {}
  for _, mem in ipairs(env.word_tables) do
    for _, code in ipairs(codes) do
      if mem:dict_lookup(code, false, 0) then
        local scanned, kept = 0, 0
        for entry in mem:iter_dict() do
          scanned = scanned + 1
          if scanned > budget or kept >= limit then break end
          local text = entry.text
          if not taken[text] and not (seen and seen[text]) and matches(env, text, slice) then
            taken[text] = true
            kept = kept + 1
            found[#found + 1] = { text = text, weight = entry.weight, order = #found }
          end
        end
      end
    end
  end
  table.sort(found, function(a, b)
    if a.weight ~= b.weight then return a.weight > b.weight end
    return a.order < b.order
  end)
  for i = #found, limit + 1, -1 do found[i] = nil end
  return found
end

local function best_word(env, parts, from, to)
  return lookup_words(env, parts, from, to, 1)[1]
end

local function sentence(env, parts)
  local n = #parts
  local best = { [n + 1] = { groups = 0, weight = 0, text = "" } }
  for i = n, 1, -1 do
    for len = 2, MAX_WORD_PHRASE do
      local rest = best[i + len]
      if rest and i + len - 1 <= n then
        local entry = best_word(env, parts, i, i + len - 1)
        if entry then
          local groups, weight = rest.groups + 1, rest.weight + entry.weight
          local cur = best[i]
          if not cur or groups < cur.groups or (groups == cur.groups and weight > cur.weight) then
            best[i] = { groups = groups, weight = weight, text = entry.text .. rest.text }
          end
        end
      end
    end
  end
  return best[1] and best[1].groups > 1 and best[1].text or nil
end

-- 三段起，也给前两段、前三段的词（只吃掉这几段和后面的分词号），
-- 选了接着打剩下的，和拼音选词一样一段一段上屏。
local function emit_phrase_heads(env, parts, seg, emit, seen)
  for len = math.min(#parts - 1, MAX_WORD_PHRASE - 1), 2, -1 do
    local chars = 0
    for i = 1, len do chars = chars + #parts[i] + 1 end
    for _, item in ipairs(lookup_words(env, parts, 1, len, MAX_HEADS, seen)) do
      emit(item.text, seg.start + chars)
    end
  end
end

-- ============================================================
-- 二、短码补字（原 V4 cyime_stroke_head2.lua，逻辑原样保留）
-- ============================================================

local function user_words(env, head)
  local res = {}
  if not env.chars then return res end
  local ok = pcall(function() return env.chars:user_lookup(head, true) end)
  if not ok then return res end
  local ranked = {}
  pcall(function()
    for e in env.chars:iter_user() do
      local t = e.text
      if utf8.len(t) == 1 then
        ranked[#ranked + 1] = { text = t, weight = e.weight or 0 }
      end
    end
  end)
  table.sort(ranked, function(a, b) return a.weight > b.weight end)
  local seen = {}
  for _, r in ipairs(ranked) do
    if not seen[r.text] then seen[r.text] = true; res[#res + 1] = r.text end
  end
  return res
end

local function static_words(env, head)
  local exact, partial, dedup = {}, {}, {}
  if not env.chars then return { exact = exact, partial = partial } end
  if env.chars:dict_lookup(head, false, 0) then
    for e in env.chars:iter_dict() do
      local t = e.text
      if utf8.len(t) == 1 and not dedup[t] then
        dedup[t] = true; exact[#exact + 1] = t
      end
    end
  end
  if env.chars:dict_lookup(head, true, 0) then
    local ranked = {}
    for e in env.chars:iter_dict() do
      local t = e.text
      if utf8.len(t) == 1 and not dedup[t] then
        dedup[t] = true
        ranked[#ranked + 1] = { text = t, weight = e.weight or 0 }
      end
    end
    table.sort(ranked, function(a, b) return a.weight > b.weight end)
    for i, r in ipairs(ranked) do partial[i] = r.text end
  end
  return { exact = exact, partial = partial }
end

local function cached_static(env, head)
  local static = env.cache[head]
  if not static then
    static = static_words(env, head)
    env.cache_n = env.cache_n + 1
    if env.cache_n > CACHE_MAX then env.cache, env.cache_n = {}, 0 end
    env.cache[head] = static
  end
  return static
end

-- 笔画打得比哪个字都长时（#445：连打 nzhspnph 没有一个字的笔画是这样开头的），往前退到还有字的那几笔，
-- 出这几笔的字，选了上屏，后面多打的笔画留着接着打。不然候选清空，编码也变回字母。
-- 返回能对上字的笔数，一个都对不上返回 nil。
-- 只看有没有字以这几笔开头，取到一个就停（短编码开头的字成千上万，不能全取出来排序）。
local function has_char(env, code)
  if not env.chars:dict_lookup(code, true, 1) then return false end
  for _ in env.chars:iter_dict() do return true end
  return false
end

local function longest_head(env, head)
  for k = #head - 1, 1, -1 do
    local code = head:sub(1, k)
    if has_char(env, code) then return k, cached_static(env, code) end
  end
  return nil
end

local function emit_char_heads(env, parts, seg)
  if not env.chars then return end
  local head = parts[1]
  -- 第一段空着（先打了分词号）：不知道第一个字，不补单字。
  if head == "" then return end
  local static = cached_static(env, head)
  local stop = seg.start + #head + 1
  local shown = head .. "'"
  if #static.exact == 0 and #static.partial == 0 then
    -- 第一段打多了，补的字只盖住对得上的那几笔。
    local k, prefix = longest_head(env, head)
    if not k then return end
    head, static = head:sub(1, k), prefix
    stop, shown = seg.start + k, head
  end
  local seen = {}
  local function out(list)
    for _, t in ipairs(list) do
      if not seen[t] then
        seen[t] = true
        local cand = Candidate("stroke", seg.start, stop, t, "")
        cand.quality = HEAD_QUALITY
        cand.preedit = stroke_preedit(shown)
        yield(cand)
      end
    end
  end
  out(user_words(env, head))
  out(static.exact)
  out(static.partial)
end

-- ============================================================
-- 三、自动造词（原 V3 cyime_stroke_learn.lua，含两处修复）
-- ============================================================

-- 学到的词按每个字的第一笔记（和词表一样，编码前加 PREFIX），查的时候和词表词一样按每段笔画对字，
-- 所以下次只打头一两笔、或者笔画多打少打几笔都能出来（11-19：以前只认一模一样的整串，等于没记）。
-- 以前按整串记的词（=pzps'zphn）照样能查到。
-- 一个字一个字上屏的，连着的两到四个字先记在 SEEN 名下（不出候选）；同样几个字第二次连着上屏才算词，
-- 记成 commits 为 0（没被整个选过），排在词表前几个词后面；被整个选过一次就排最前。
-- 只出现一次的连字（的猫、娘要）不进候选，免得每打一个字就多一堆怪词。
local HISTORY_MAX = 4          -- 逐字上屏连起来最多记四个字
local HISTORY_GAP = 20         -- 两次上屏隔了这么多秒就不连
local HISTORY_AFTER = 3        -- 没选过的连字词排在词表前三个词后面

-- 词的每种首笔编码（个别字有两种笔顺，最多 4 种），字没有笔画码时返回 nil。
local function word_codes(env, text)
  local codes = { "" }
  for _, cp in utf8.codes(text) do
    local firsts, have = {}, {}
    for _, code in ipairs(codes_of(env, utf8.char(cp))) do
      local f = code:sub(1, 1)
      if f ~= "" and not have[f] then have[f] = true; firsts[#firsts + 1] = f end
    end
    if #firsts == 0 then return nil end
    local expanded = {}
    for _, prefix in ipairs(codes) do
      for _, f in ipairs(firsts) do
        if #expanded < 4 then expanded[#expanded + 1] = prefix .. f end
      end
    end
    codes = expanded
  end
  return codes
end

local function learned(env, input, parts)
  local keys = {}
  for _, code in ipairs(first_codes(parts, 1, #parts) or {}) do keys[#keys + 1] = PREFIX .. code end
  keys[#keys + 1] = PREFIX .. input
  local found, at = {}, {}
  local ok = pcall(function()
    for _, key in ipairs(keys) do
      if env.mem:user_lookup(key, true) then
        for entry in env.mem:iter_user() do
          local text = entry.text
          local commits = entry.commit_count or 0
          if commits >= 0 and han_len(text) == #parts and matches(env, text, parts) then
            local i = at[text]
            if not i then
              found[#found + 1] = { text = text, commits = commits, order = #found, entry = entry }
              at[text] = #found
            elseif commits > found[i].commits then
              found[i].commits, found[i].entry = commits, entry
            end
          end
        end
      end
    end
  end)
  if not ok then return nil end
  table.sort(found, function(a, b)
    if a.commits ~= b.commits then return a.commits > b.commits end
    return a.order < b.order
  end)
  return found
end

local SEEN = "~"

-- 一个词只记一条（一种首笔编码），长按「忘记」删掉它就真没了（11-19）。[code] 是打的时候用的首笔
-- （个别字有两种笔顺，按用户打的那种记，下次同样打法才查得到）；没给时用码表第一种笔顺。
local function save_word(env, text, commits, prefix, code)
  if not code then
    local codes = word_codes(env, text)
    if not codes then return end
    code = codes[1]
  end
  local made, entry = pcall(DictEntry)
  if not made or not entry then return end
  entry.text = text
  entry.custom_code = (prefix or PREFIX) .. code
  entry.weight = 0
  pcall(function() return env.mem:update_userdict(entry, commits, "") end)
end

-- 每种首笔编码都看：整词选的按打的笔顺记，可能不是码表第一种。
local function has_word(env, prefix, text)
  local codes = word_codes(env, text)
  if not codes then return false end
  local ok, found = pcall(function()
    for _, code in ipairs(codes) do
      if env.mem:user_lookup(prefix .. code, true) then
        for entry in env.mem:iter_user() do
          if entry.text == text and (entry.commit_count or 0) >= 0 then return true end
        end
      end
    end
    return false
  end)
  return ok and found
end

-- 词表里已经有的词不用逐字连着记。
local function in_phrases(env, text)
  if not env.phrases then return false end
  local codes = word_codes(env, text)
  if not codes or not env.phrases:dict_lookup(codes[1], false, 0) then return false end
  local scanned = 0
  for entry in env.phrases:iter_dict() do
    if entry.text == text then return true end
    scanned = scanned + 1
    if scanned > SCAN_EXACT then break end
  end
  return false
end

-- 分词打出来的词按打的首笔记；空段（没打笔画的字）用码表第一种笔顺。对不上时返回 nil。
local function typed_code(env, text, input)
  local parts = input and split(input)
  if not parts or han_len(text) ~= #parts or not matches(env, text, parts) then return nil end
  local firsts, i = {}, 0
  for _, cp in utf8.codes(text) do
    i = i + 1
    local first = parts[i]:sub(1, 1)
    if first == "" then first = (codes_of(env, utf8.char(cp))[1] or ""):sub(1, 1) end
    if first == "" then return nil end
    firsts[i] = first
  end
  return table.concat(firsts)
end

local function on_commit(ctx, env)
  local ok, text = pcall(function() return ctx:get_commit_text() end)
  local typed = env.pending_code
  env.pending_code = nil
  if not ok or not text or text == "" then return end
  local n = han_len(text)
  local now = os.time()
  -- 标点、字母、隔了很久：不和前面的字连成词。
  if not n or (env.history_at and now - env.history_at > HISTORY_GAP) then env.history = {} end
  env.history_at = now
  if not n then return end
  if n >= MIN_WORD then
    -- 整个选的词（分词打的、词表的、通配组的）：记一次，下次排在最前。
    if n <= MAX_WORD_LEARN then save_word(env, text, 1, PREFIX, typed_code(env, text, typed)) end
    env.history = {}
    return
  end
  -- 一个字一个字上屏：和前面连着上屏的字连成两到四字的词，第二次这样连着打才记成词，先不往前排。
  local history = env.history
  history[#history + 1] = text
  while #history > HISTORY_MAX do table.remove(history, 1) end
  for len = MIN_WORD, #history do
    local word = table.concat(history, "", #history - len + 1, #history)
    if not has_word(env, PREFIX, word) and not in_phrases(env, word) then
      if has_word(env, SEEN, word) then
        -- 第二次连着打：记成词，「连着打过一次」的记录作废。词被「忘记」以后要再连着打两次才回来。
        save_word(env, word, 0)
        save_word(env, word, -1, SEEN)
      else
        save_word(env, word, 1, SEEN)
      end
    end
  end
end

-- 每次都现查用户词典，不缓存：长按「忘记」时引擎直接删记录（不经过 lua），缓存会让删掉的词还留在候选里。
-- 一般只查一两个编码，比打一个字的其余工作量小得多。
local function learned_for(env, input, parts)
  if not env.mem then return {} end
  return learned(env, input, parts) or {}
end

-- 学到的词用用户词典里那条记录造候选（Phrase），长按「忘记」时引擎按这条记录删（rime_jni forgetCandidate）；
-- 以前是普通候选，引擎认不出是学来的，忘不掉（11-19 平板）。
local function learned_candidate(env, item, seg, input)
  local quality = BASE_QUALITY + math.min(item.commits, 4)
  local ok, cand = pcall(function()
    local phrase = Phrase(env.mem, "stroke", seg.start, seg._end, item.entry)
    phrase.quality = quality
    phrase.preedit = stroke_preedit(input)
    phrase.comment = ""
    return phrase:toCandidate()
  end)
  if ok and cand then return cand end
  cand = Candidate("stroke", seg.start, seg._end, item.text, "")
  cand.quality = quality
  cand.preedit = stroke_preedit(input)
  return cand
end

local function emit_learned(env, input, seg, list, used)
  for _, item in ipairs(list) do
    if (item.commits > 0) == used then yield(learned_candidate(env, item, seg, input)) end
  end
end

-- ============================================================
-- 生命周期
-- ============================================================

function M.init(env)
  glyphs = env.engine.schema.config:get_string("translator/preedit_format/@0") ~= nil
  env.codes = {}
  env.cache, env.cache_n = {}, 0
  env.history, env.history_at = {}, nil
  env.pending_code = nil
  env.conn = nil

  -- 内置分词打词用的词表与笔顺反查
  pcall(function() env.phrases = Memory(env.engine, Schema("stroke_phrase")) end)
  -- 用户导入的词库（设置「导入词库」里选上的），应用词库时换成每字首笔写成 stroke_cells（StrokeCellDictionary.kt）。
  -- 没导入时没有这个词表，只查内置的。导入的词权重低，同一编码下排在内置词后面。
  env.word_tables = { env.phrases }
  local config = env.engine.schema.config
  local imported = false
  pcall(function()
    for i = 0, (config:get_list_size("schema/dependencies") or 0) - 1 do
      if config:get_string("schema/dependencies/@" .. i) == "stroke_cells" then imported = true end
    end
  end)
  if imported then
    pcall(function()
      local cells = Memory(env.engine, Schema("stroke_cells"))
      if cells then env.word_tables[#env.word_tables + 1] = cells end
    end)
  end
  pcall(function() env.strokes = ReverseLookup("stroke") end)
  -- 补字与造词各用一个独立 Memory，互不干扰
  pcall(function() env.chars = Memory(env.engine, env.engine.schema) end)
  pcall(function() env.mem = Memory(env.engine, env.engine.schema) end)

  if env.mem then
    pcall(function()
      env.conn = env.engine.context.commit_notifier:connect(function(ctx)
        pcall(on_commit, ctx, env)
      end)
    end)
  end
end

function M.fini(env)
  if env.conn and env.conn.disconnect then pcall(env.conn.disconnect, env.conn) end
  env.conn = nil
  env.phrases, env.strokes, env.chars, env.mem = nil, nil, nil, nil
  env.word_tables = nil
  env.codes = nil
  env.cache, env.cache_n = nil, nil
  env.history, env.history_at = nil, nil
  env.pending_code = nil
  collectgarbage()
end

-- 没打分词号、整串笔画又没有一个字对得上（#445）：内置的整字查询什么都不出，这里补上前面对得上的那几笔的字。
local function emit_overlong(env, input, seg)
  if not env.chars or input:find("[^hspnz]") or has_char(env, input) then return end
  emit_char_heads(env, { input }, seg)
end

function M.func(input, seg, env)
  local parts = split(input)
  if not parts then
    emit_overlong(env, input, seg)
    return
  end

  -- 记下这次组词打的整串（上屏时按它记词的首笔）。逐字选词时剩下的编码是整串的后半截，不覆盖。
  local pending = env.pending_code
  if not pending or #input > #pending or pending:sub(-#input) ~= input then
    env.pending_code = input
  end

  -- 顺序要紧：学过的词必须先 yield。补字会吐几百上千个单字候选，librime
  -- 取够候选即停止迭代，放后面就没机会执行了。
  local learned_list = learned_for(env, input, parts)
  emit_learned(env, input, seg, learned_list, true)
  local fresh_pending = true
  local function emit_fresh()
    if fresh_pending then
      fresh_pending = false
      emit_learned(env, input, seg, learned_list, false)
    end
  end
  local learned_seen = {}
  for _, item in ipairs(learned_list) do learned_seen[item.text] = true end

  -- 内置词表：整词 / 拼句 / 更长的词
  if env.phrases then
    local seen = {}
    for text in pairs(learned_seen) do seen[text] = true end
    local function emit(text, stop)
      seen[text] = true
      local cand = Candidate("stroke_phrase", seg.start, stop or seg._end, text, "")
      cand.quality = QUALITY
      cand.preedit = stroke_preedit(input:sub(1, (stop or seg._end) - seg.start))
      yield(cand)
    end

    local exact = 0
    if #parts <= MAX_WORD_PHRASE then
      for _, item in ipairs(lookup_words(env, parts, 1, #parts, MAX_EXACT, seen)) do
        exact = exact + 1
        emit(item.text)
        if exact == HISTORY_AFTER then emit_fresh() end
      end
    end
    emit_fresh()

    if #parts >= 4 and exact == 0 then
      local text = sentence(env, parts)
      if text and not seen[text] then emit(text) end
    end

    if #parts >= MAX_WORD_PHRASE then
      emit_phrase_heads(env, parts, seg, emit, seen)
    elseif has_empty(parts) then
      -- 有空段时只给正好 n+1 个字的词（和前几个字的词），不往更长的词猜：空段已经说了这里有几个字。
      if #parts >= 3 then emit_phrase_heads(env, parts, seg, emit, seen) end
    else
      local firsts = {}
      for i, part in ipairs(parts) do firsts[i] = part:sub(1, 1) end
      local code = table.concat(firsts)
      local scanned
      local longer = {}
      for _, next_stroke in ipairs({ "h", "s", "p", "n", "z" }) do
        scanned = 0
        if env.phrases:dict_lookup(code .. next_stroke, true, 0) then
          for entry in env.phrases:iter_dict() do
            scanned = scanned + 1
            if scanned > SCAN_LONGER then break end
            local text = entry.text
            if not seen[text] and matches(env, text, parts) then
              seen[text] = true
              longer[#longer + 1] = { text = text, weight = entry.weight, order = #longer }
            end
          end
        end
      end
      table.sort(longer, function(a, b)
        if a.weight ~= b.weight then return a.weight > b.weight end
        return a.order < b.order
      end)
      for i = 1, math.min(#longer, MAX_LONGER) do emit(longer[i].text) end
      if #parts >= 3 then emit_phrase_heads(env, parts, seg, emit, seen) end
    end
  end

  emit_fresh()
  -- 补字单字放最后（quality 最低，本来就该排在词后面）
  emit_char_heads(env, parts, seg)
end

return M
