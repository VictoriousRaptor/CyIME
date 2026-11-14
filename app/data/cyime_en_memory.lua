--[[
  CyIME：记住自己打的英文词（Obsidian）。
  中文 26 键里回车上屏的英文字母串由输入法记到用户目录的 cyime_en_memory.txt（一行一个，按打的大小写，新的在后，
  见 EnglishWordMemory.kt）；空格或点选上屏、拼写与所打字母相同（不分大小写）且带大写的英文词（打 Obsidian 选了
  词库的 Obsidian）由这里记下，无痕时不记。词库里没有也能再打出来：
  - 输入与记住的词相同（打了大写时大小写也要相同）：打了大写或首选本来就是英文时放第一位，否则放第五位，
    前四个候选不动（回车也会上屏拼音字母，nihao、shi 被记住后不能挤到你好、是前面）。
  - 输入是记住的词的开头（3 个字母起）：同样的规则放第三位或前四个之后，最近打过的优先，最多两个。
  输入不是纯字母或文件里没有对应词时不做任何事。
  长按删除过的词库英文词（cyime_en_demoted.txt，小写一行一个，见 EnglishWordMemory.kt；2026-11-13 删不掉的英文首选）：
  只打小写时排到前四个候选之后；打了大写照常。再选它上屏就从文件里去掉，回到原位。
--]]
local FILE = "/cyime_en_memory.txt"
local DEMOTED_FILE = "/cyime_en_demoted.txt"
local MAX_PREFIX = 2
-- Same limit and compaction as EnglishWordMemory.kt: past twice the limit the file is rewritten with the
-- latest spelling of each word, the newest MAX_WORDS kept.
local MAX_WORDS = 2000

local cache = { key = nil, words = {} }

local function load_words()
    local dir = rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir()
    if not dir then return {} end
    local f = io.open(dir .. FILE, "r")
    if not f then
        cache.key, cache.words = nil, {}
        return cache.words
    end
    -- Size plus the last bytes notice an append, a compaction and a forgotten word alike.
    local size = f:seek("end") or 0
    f:seek("set", math.max(0, size - 64))
    local key = size .. ":" .. (f:read("*a") or "")
    if key ~= cache.key then
        f:seek("set", 0)
        local words = {}
        for line in f:lines() do
            local w = line:match("^%s*(%a+)%s*$")
            if w then words[#words + 1] = w end
        end
        cache.key, cache.words = key, words
    end
    f:close()
    return cache.words
end

local demoted_cache = { key = nil, words = {} }

-- Lowercase words the user pushed back; re-read when the file changes (size plus its last bytes).
local function load_demoted()
    local dir = rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir()
    if not dir then return {} end
    local f = io.open(dir .. DEMOTED_FILE, "r")
    if not f then
        demoted_cache.key, demoted_cache.words = nil, {}
        return demoted_cache.words
    end
    local size = f:seek("end") or 0
    f:seek("set", math.max(0, size - 64))
    local key = size .. ":" .. (f:read("*a") or "")
    if key ~= demoted_cache.key then
        f:seek("set", 0)
        local words = {}
        for line in f:lines() do
            local w = line:match("^%s*(%a+)%s*$")
            if w then words[w:lower()] = true end
        end
        demoted_cache.key, demoted_cache.words = key, words
    end
    f:close()
    return demoted_cache.words
end

-- A pushed-back word picked again comes back: it leaves the file.
local function undemote(word)
    local dir = rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir()
    if not dir or not load_demoted()[word:lower()] then return end
    local f = io.open(dir .. DEMOTED_FILE, "r")
    if not f then return end
    local kept = {}
    for line in f:lines() do
        local w = line:match("^%s*(%a+)%s*$")
        if w and w:lower() ~= word:lower() then kept[#kept + 1] = w end
    end
    f:close()
    local tmp = dir .. DEMOTED_FILE .. ".tmp"
    local out = io.open(tmp, "w")
    if not out then return end
    for _, w in ipairs(kept) do out:write(w, "\n") end
    out:close()
    if not os.rename(tmp, dir .. DEMOTED_FILE) then
        os.remove(dir .. DEMOTED_FILE)
        os.rename(tmp, dir .. DEMOTED_FILE)
    end
end

-- Typed capitals, or an English first candidate, mean English is wanted: the word may lead. Otherwise the first
-- four candidates (Chinese words, the date for rq, …) keep their places.
local function english_first(cand, upper)
    return upper or (cand ~= nil and cand.text:match("^%a[%a%p ]*$") ~= nil)
end

-- A committed English word the user typed with capitals is remembered as spelled (Obsidian), so typing obsidian
-- offers it again. Enter on raw letters is remembered by the app (EnglishWordMemory.kt).
local function remember(word)
    local dir = rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir()
    if not dir then return end
    local words = load_words()
    if words[#words] == word then return end
    if #words < MAX_WORDS * 2 then
        local f = io.open(dir .. FILE, "a")
        if f then
            f:write(word, "\n")
            f:close()
        end
        return
    end
    local kept, seen = {}, {}
    for i = #words + 1, 1, -1 do
        local w = words[i] or word
        if not seen[w] and #kept < MAX_WORDS then
            seen[w] = true
            kept[#kept + 1] = w
        end
    end
    local tmp = dir .. FILE .. ".tmp"
    local f = io.open(tmp, "w")
    if not f then return end
    for i = #kept, 1, -1 do f:write(kept[i], "\n") end
    f:close()
    if not os.rename(tmp, dir .. FILE) then
        os.remove(dir .. FILE)
        os.rename(tmp, dir .. FILE)
    end
end

local function init(env)
    env.commit_connection = env.engine.context.commit_notifier:connect(function(ctx)
        if ctx:get_option("_cyime_incognito") then return end
        local text, input = ctx:get_commit_text(), ctx.input
        if text:match("^%a+$") then undemote(text) end
        if #text >= 2 and #text <= 40 and text:match("^%a+$") and text:find("%u") and text:lower() == input:lower() then
            remember(text)
        end
    end)
end

local function fini(env)
    if env.commit_connection then env.commit_connection:disconnect() end
end

local function remembered_order(input, env, yield)
    local code = env.engine.context.input
    if #code < 2 or #code > 40 or not code:match("^%a+$") then
        for cand in input:iter() do yield(cand) end
        return
    end
    local words = load_words()
    local lower = code:lower()
    -- Typed capitals spell the word: KFC does not bring back a remembered lowercase kfc.
    local upper = code:find("%u") ~= nil
    local typed = upper and code or lower
    local exact, prefixes, seen = nil, {}, {}
    for i = #words, 1, -1 do
        local w = words[i]
        local lw = upper and w or w:lower()
        if not seen[w] then
            seen[w] = true
            if lw == typed then
                exact = exact or w
            elseif #lower >= 3 and #prefixes < MAX_PREFIX and lw:sub(1, #typed) == typed then
                prefixes[#prefixes + 1] = w
            end
        end
    end
    if not exact and #prefixes == 0 then
        for cand in input:iter() do yield(cand) end
        return
    end

    local head, rest_started = {}, false
    local function emit_head()
        local offset = english_first(head[1], upper) and 0 or 4
        local out, inserted = {}, {}
        local function add(text)
            if inserted[text] then return end
            inserted[text] = true
            out[#out + 1] = Candidate("cyime_en", 0, #code, text, "")
        end
        local slots = {}
        if exact then slots[1 + offset] = { exact } end
        if #prefixes > 0 then slots[3 + offset] = prefixes end
        local pos, i = 1, 1
        while i <= #head or next(slots) do
            if slots[pos] then
                for _, text in ipairs(slots[pos]) do add(text) end
                slots[pos] = nil
            elseif i <= #head then
                if not inserted[head[i].text] then out[#out + 1] = head[i] end
                i = i + 1
            else
                -- Fewer candidates than the slot: the remaining words follow.
                for k, list in pairs(slots) do
                    for _, text in ipairs(list) do add(text) end
                    slots[k] = nil
                end
            end
            pos = #out + 1
        end
        for _, cand in ipairs(out) do yield(cand) end
        return inserted
    end

    local inserted
    for cand in input:iter() do
        if rest_started then
            if not inserted[cand.text] then yield(cand) end
        else
            head[#head + 1] = cand
            if #head >= 4 then
                inserted = emit_head()
                rest_started = true
            end
        end
    end
    if not rest_started then emit_head() end
end

-- Pushed-back dictionary English words (lowercase input only) wait until four other candidates have gone out; when
-- the input itself is such a word (mean), its English completions (means, meaning) wait too, or one of them led.
local function cyime_en_memory(input, env)
    local code = env.engine.context.input
    local demoted = (code:match("^%l+$") and env.engine.schema.schema_id ~= "cyime_english") and load_demoted() or nil
    if not demoted or next(demoted) == nil then
        remembered_order(input, env, yield)
        return
    end
    local held, emitted = {}, 0
    local typed_demoted = demoted[code] == true
    local function emit(cand)
        local lower = cand.text:match("^%a+$") and cand.text:lower()
        if emitted < 4 and lower and (demoted[lower] or (typed_demoted and lower:sub(1, #code) == code)) then
            held[#held + 1] = cand
            return
        end
        yield(cand)
        emitted = emitted + 1
        if emitted == 4 then
            for _, h in ipairs(held) do yield(h) end
            held = {}
        end
    end
    remembered_order(input, env, emit)
    for _, h in ipairs(held) do yield(h) end
end

return { init = init, func = cyime_en_memory, fini = fini }
