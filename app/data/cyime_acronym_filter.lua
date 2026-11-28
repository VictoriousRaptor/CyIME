--[[
  CyIME：小写打英文缩写（kfc → KFC）。
  英文词库给 2–3 个大写字母的缩写派生了小写编码（melt_eng 方案由构建脚本加的 derive）。
  这类输入同时是中文简拼（kfc 也是“开发出”），英文候选通常排在很后面，所以：前四个候选保持原样，
  在前 60 个候选里找到的第一个缩写挪到第五位（本来就在前四的不动，如 aa → AA、vip → VIP）。
  只打一个字母（i、u、v 除外）时，英文补全（a → Adult Video、Anti-lock Braking System…）不出：
  一个字母后面翻好几页英文没有用；整词就是这个字母的英文（a、A）照常出。其他输入不做任何事。
--]]
local SCAN = 60
-- 单字母时连续这么多个英文补全后就不再往下取：补全排在所有中文之后，后面不会再有中文。
local SKIP_RUN = 32

local function cyime_acronym_filter(input, env)
    local code = env.engine.context.input
    if code:match("^[a-hj-tw-z]$") then
        local skipped = 0
        for cand in input:iter() do
            if cand.type == "completion" then
                skipped = skipped + 1
                if skipped >= SKIP_RUN then return end
            else
                skipped = 0
                yield(cand)
            end
        end
        return
    end
    if not code:match("^%l%l%l?$") then
        for cand in input:iter() do yield(cand) end
        return
    end
    local head, acronym, scanned, streaming = {}, nil, 0, false
    for cand in input:iter() do
        if streaming then
            if cand ~= acronym then yield(cand) end
        else
            scanned = scanned + 1
            -- Already among the first four (aa → AA, vip → VIP): it keeps its place.
            if not acronym and scanned > 4 and cand.text:match("^%u+$") and cand.text:lower() == code then
                acronym = cand
            else
                table.insert(head, cand)
            end
            if #head >= 4 and (acronym or scanned >= SCAN) then
                for i = 1, 4 do yield(head[i]) end
                if acronym then yield(acronym) end
                for i = 5, #head do yield(head[i]) end
                streaming = true
            end
        end
    end
    if not streaming then
        for i = 1, math.min(4, #head) do yield(head[i]) end
        if acronym then yield(acronym) end
        for i = 5, #head do yield(head[i]) end
    end
end

-- 只有一到三个小写字母的输入才做事（见上），其余输入让 Rime 整个跳过这个滤镜，不进 Lua 循环。
local function tags_match(seg, env)
    local code = env.engine.context.input
    return code:match("^[a-hj-tw-z]$") ~= nil or code:match("^%l%l%l?$") ~= nil
end

return { func = cyime_acronym_filter, tags_match = tags_match }
