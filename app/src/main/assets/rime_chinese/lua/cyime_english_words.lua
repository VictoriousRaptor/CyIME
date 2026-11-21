--[[
  CyIME 英文单词方案（cyime_english.schema.yaml）的候选整理：
  - 大小写跟着打的字母走：首字母大写（Hello）候选首字母大写，两个字母以上全大写（HELLO）候选全大写；
    小写打时词库里本来带大写的词（iPhone、NASA、Aaron）照原样。
  - 所打字母不是首选的整词时（hel 的首选是 hello），原样的字母排第二位（回车上屏的也是它）；
    一个候选都没有（词库没有的词）时原样字母就是唯一候选，空格照样上屏。
  - 首字母是「首字母大写」自动打成大写的（输入法设选项 _cyime_autocap）：首选后面再给一个小写的（Hello 后有 hello）；
    在这种时候选过小写的词记进用户目录的 cyime_en_lower.txt（一行一个），下次自动大写时小写的排在前面，
    又选回大写的就从文件里去掉（#465）。
  标点等不是字母的候选不动。
--]]
local LOWER_FILE = "/cyime_en_lower.txt"
local MAX_LOWER = 2000

local function recase(text, code)
    if not code:find("^%u") then return text end
    if #code > 1 and not code:find("%l") then return text:upper() end
    return text:sub(1, 1):upper() .. text:sub(2)
end

local function is_word_code(code)
    return code:match("^%a[%a']*$") ~= nil
end

local lower_cache = { key = nil, words = {}, list = {} }

local function user_dir()
    return rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir()
end

-- Words picked lowercase at an auto-capitalized sentence start; re-read when the file changes (size plus last bytes).
local function load_lower()
    local dir = user_dir()
    if not dir then return lower_cache end
    local f = io.open(dir .. LOWER_FILE, "r")
    if not f then
        lower_cache = { key = nil, words = {}, list = {} }
        return lower_cache
    end
    local size = f:seek("end") or 0
    f:seek("set", math.max(0, size - 64))
    local key = size .. ":" .. (f:read("*a") or "")
    if key ~= lower_cache.key then
        f:seek("set", 0)
        local words, list = {}, {}
        for line in f:lines() do
            local w = line:match("^%s*(%l[%a']*)%s*$")
            if w and not words[w] then
                words[w] = true
                list[#list + 1] = w
            end
        end
        lower_cache = { key = key, words = words, list = list }
    end
    f:close()
    return lower_cache
end

local function save_lower(list)
    local dir = user_dir()
    if not dir then return end
    local tmp = dir .. LOWER_FILE .. ".tmp"
    local out = io.open(tmp, "w")
    if not out then return end
    local first = math.max(1, #list - MAX_LOWER + 1)
    for i = first, #list do out:write(list[i], "\n") end
    out:close()
    if not os.rename(tmp, dir .. LOWER_FILE) then
        os.remove(dir .. LOWER_FILE)
        os.rename(tmp, dir .. LOWER_FILE)
    end
end

local function auto_capped(ctx, code)
    return ctx:get_option("_cyime_autocap") and code:match("^%u[%l']*$") ~= nil
end

local function init(env)
    env.commit_connection = env.engine.context.commit_notifier:connect(function(ctx)
        if ctx:get_option("_cyime_incognito") then return end
        local input, text = ctx.input, ctx:get_commit_text()
        if not auto_capped(ctx, input) or not text:match("^%a[%a']*$") then return end
        if text:lower():sub(1, #input) ~= input:lower() then return end
        local lower = load_lower()
        if text:match("^%l") then
            if not lower.words[text] then
                local list = {}
                for i, w in ipairs(lower.list) do list[i] = w end
                list[#list + 1] = text
                save_lower(list)
            end
        elseif lower.words[text:sub(1, 1):lower() .. text:sub(2)] then
            local back, list = text:sub(1, 1):lower() .. text:sub(2), {}
            for _, w in ipairs(lower.list) do
                if w ~= back then list[#list + 1] = w end
            end
            save_lower(list)
        end
    end)
end

local function fini(env)
    if env.commit_connection then env.commit_connection:disconnect() end
end

local function cyime_english_words(input, env)
    local ctx = env.engine.context
    local first = true
    local keep_lower = nil
    for cand in input:iter() do
        local code = ctx.input:sub(cand.start + 1, cand._end)
        if not is_word_code(code) then
            yield(cand)
        else
            local text = recase(cand.text, code)
            -- Auto-capitalized start: the dictionary's own lowercase spelling stays available, first for words the
            -- user picked lowercase before.
            local lowered = nil
            if text ~= cand.text and cand.text:match("^%l") and auto_capped(ctx, code) then
                keep_lower = keep_lower or load_lower().words
                lowered = cand.text
            end
            local prefer_lower = lowered ~= nil and keep_lower[lowered]
            if prefer_lower then
                yield(cand)
                if first then yield(ShadowCandidate(cand, cand.type, text, cand.comment)) end
            elseif text ~= cand.text then
                yield(ShadowCandidate(cand, cand.type, text, cand.comment))
                if first and lowered then yield(cand) end
            else
                yield(cand)
            end
            if first then
                first = false
                if text:lower() ~= code:lower() then
                    yield(Candidate("raw", cand.start, cand._end, code, ""))
                end
            end
        end
    end
    if first then
        local seg = ctx.composition:back()
        if seg then
            local code = ctx.input:sub(seg.start + 1, seg._end)
            if is_word_code(code) then yield(Candidate("raw", seg.start, seg._end, code, "")) end
        end
    end
end

return { init = init, func = cyime_english_words, fini = fini }
