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
--   3) 自动造词：逐字选出的 2~8 字纯汉字组合自动记住，下次打同串出整词。
--      修了原版「逐字选词时剩余编码覆盖完整编码 → 三字以上学不会」的 bug，
--      并把上限由 4 字放宽到 8 字。
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

-- lua 造的候选没有 preedit 时，Rime 拿原始字母当编码显示（#372：分词后编码显示成 h s p n z）。
-- 和 stroke.schema.yaml 的 preedit_format 一样换成笔画（康熙部首）；分词号、通配符原样留着。
local STROKE_GLYPHS = { h = "⼀", s = "⼁", p = "⼃", n = "⼂", z = "⼄" }
local function stroke_preedit(code)
  return (code:gsub("[hspnz]", STROKE_GLYPHS))
end

-- 把编码按分词键切段。只允许 h/s/p/n/z 和分词键 '（含 * 的通配查询交给内置
-- wildcard 模块，这里整段不处理，避免重复给候选）。
local function split(input)
  if not input:find("'", 1, true) or input:find("[^hspnz']") then return nil end
  local parts = {}
  for part in (input .. "'"):gmatch("([^']*)'") do parts[#parts + 1] = part end
  if parts[#parts] == "" then parts[#parts] = nil end
  if #parts < 2 or #parts > MAX_SEGMENTS then return nil end
  for _, part in ipairs(parts) do
    if part == "" then return nil end
  end
  return parts
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

local function best_word(env, parts, from, to)
  local slice, firsts = {}, {}
  for i = from, to do
    slice[#slice + 1] = parts[i]
    firsts[#firsts + 1] = parts[i]:sub(1, 1)
  end
  if not env.phrases:dict_lookup(table.concat(firsts), false, 0) then return nil end
  local scanned = 0
  for entry in env.phrases:iter_dict() do
    scanned = scanned + 1
    if scanned > SCAN_EXACT then break end
    if matches(env, entry.text, slice) then return entry end
  end
  return nil
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
    local slice, firsts, chars = {}, {}, 0
    for i = 1, len do
      slice[i] = parts[i]
      firsts[i] = parts[i]:sub(1, 1)
      chars = chars + #parts[i] + 1
    end
    local found, scanned = 0, 0
    if env.phrases:dict_lookup(table.concat(firsts), false, 0) then
      for entry in env.phrases:iter_dict() do
        scanned = scanned + 1
        if scanned > SCAN_EXACT or found >= MAX_HEADS then break end
        if not seen[entry.text] and matches(env, entry.text, slice) then
          found = found + 1
          emit(entry.text, seg.start + chars)
        end
      end
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

local function emit_char_heads(env, parts, seg)
  if not env.chars then return end
  local head = parts[1]
  local static = env.cache[head]
  if not static then
    static = static_words(env, head)
    env.cache_n = env.cache_n + 1
    if env.cache_n > CACHE_MAX then env.cache, env.cache_n = {}, 0 end
    env.cache[head] = static
  end
  local stop = seg.start + #head + 1
  local seen = {}
  local function out(list)
    for _, t in ipairs(list) do
      if not seen[t] then
        seen[t] = true
        local cand = Candidate("stroke", seg.start, stop, t, "")
        cand.quality = HEAD_QUALITY
        cand.preedit = stroke_preedit(head .. "'")
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

local function learned(env, key)
  local ok, list = pcall(function()
    if not env.mem:user_lookup(key, true) then return nil end
    local found = {}
    for entry in env.mem:iter_user() do
      local cc = entry.custom_code
      if cc == nil or cc == key then
        found[#found + 1] = { text = entry.text, commits = entry.commit_count or 0 }
      end
    end
    return found
  end)
  if not ok or not list then return nil end
  return list
end

local function on_commit(ctx, env)
  local ok, text = pcall(function() return ctx:get_commit_text() end)
  if not ok or not text or text == "" then return end
  local code = env.pending_code
  env.pending_code = nil
  local n = han_len(text)
  if not n or n < MIN_WORD or n > MAX_WORD_LEARN or not code then return end
  local parts = split(code)
  if not parts or #parts ~= n then return end
  local made, entry = pcall(DictEntry)
  if not made or not entry then return end
  entry.text = text
  entry.custom_code = PREFIX .. code
  entry.weight = 0
  local saved = pcall(function() return env.mem:update_userdict(entry, 1, "") end)
  if saved then env.learn_cache, env.learn_n = {}, 0 end
end

local function emit_learned(env, input, seg)
  if not env.mem then return end
  local key = PREFIX .. input
  local list = env.learn_cache[key]
  if list == nil then
    list = learned(env, key) or false
    env.learn_n = env.learn_n + 1
    if env.learn_n > CACHE_MAX then env.learn_cache, env.learn_n = {}, 0 end
    env.learn_cache[key] = list
  end
  if not list then return end
  table.sort(list, function(a, b) return a.commits > b.commits end)
  for _, item in ipairs(list) do
    local cand = Candidate("stroke", seg.start, seg._end, item.text, "")
    cand.quality = BASE_QUALITY + math.min(item.commits, 4)
    cand.preedit = stroke_preedit(input)
    yield(cand)
  end
end

-- ============================================================
-- 生命周期
-- ============================================================

function M.init(env)
  env.codes = {}
  env.cache, env.cache_n = {}, 0
  env.learn_cache, env.learn_n = {}, 0
  env.pending_code = nil
  env.conn = nil

  -- 内置分词打词用的词表与笔顺反查
  pcall(function() env.phrases = Memory(env.engine, Schema("stroke_phrase")) end)
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
  env.codes = nil
  env.cache, env.cache_n = nil, nil
  env.learn_cache, env.learn_n = nil, nil
  env.pending_code = nil
  collectgarbage()
end

function M.func(input, seg, env)
  local parts = split(input)
  if not parts then return end

  -- 只保留本次组词过程中最长的编码。逐字选词时剩余编码（如 pp'nn）比完整
  -- 编码短，不再覆盖，上屏时才能拿到完整编码、段数才和字数对得上。
  -- 不是上一次编码的后半截（打一半清空后重打），就从这次重新记。
  local pending = env.pending_code
  if not pending or #input > #pending or pending:sub(-#input) ~= input then
    env.pending_code = input
  end

  -- 顺序要紧：学过的词必须先 yield。补字会吐几百上千个单字候选，librime
  -- 取够候选即停止迭代，放后面就没机会执行了。
  emit_learned(env, input, seg)

  -- 内置词表：整词 / 拼句 / 更长的词
  if env.phrases then
    local firsts = {}
    for i, part in ipairs(parts) do firsts[i] = part:sub(1, 1) end
    local code = table.concat(firsts)
    local seen = {}
    local function emit(text, stop)
      seen[text] = true
      local cand = Candidate("stroke_phrase", seg.start, stop or seg._end, text, "")
      cand.quality = QUALITY
      cand.preedit = stroke_preedit(input:sub(1, (stop or seg._end) - seg.start))
      yield(cand)
    end

    local exact, scanned = 0, 0
    if #parts <= MAX_WORD_PHRASE and env.phrases:dict_lookup(code, false, 0) then
      for entry in env.phrases:iter_dict() do
        scanned = scanned + 1
        if scanned > SCAN_EXACT or exact >= MAX_EXACT then break end
        if not seen[entry.text] and matches(env, entry.text, parts) then
          exact = exact + 1
          emit(entry.text)
        end
      end
    end

    if #parts >= 4 and exact == 0 then
      local text = sentence(env, parts)
      if text and not seen[text] then emit(text) end
    end

    if #parts >= MAX_WORD_PHRASE then
      emit_phrase_heads(env, parts, seg, emit, seen)
    else
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

  -- 补字单字放最后（quality 最低，本来就该排在词后面）
  emit_char_heads(env, parts, seg)
end

return M
