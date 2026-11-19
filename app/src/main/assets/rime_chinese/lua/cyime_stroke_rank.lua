-- 笔画方案的候选排序：笔画打全的常用字在前，其后是还没打完的常用字（按字频），生僻字最后。
-- Rime 原本把没打完的字按码长排，常用字排在生僻字后面（丿丶一丨 先出 仐 仝 㒰，后出 合）。
-- 只重排前 200 个候选，后面的照原样跟上。
-- 笔画打全但很少用的字（乛 的 〇）不压在最常用的没打完的字（了）前面：字频不到其千分之一时，
-- 放在没打完的前 4 个字之后，仍在第一页。
-- 打全了但几乎没人用的部首、怪字（丿㇏ 的 乂，一一丨 的 亍，字频不到十万分之一）再往后放，
-- 排在没打完的前 10 个常用字之后，不挤进第一页（11-18 贡献者）。
local LIMIT = 200
local MINOR = 1e-3
local MINOR_AFTER = 4
local ODD = 1e-5
local ODD_AFTER = 10
-- 字频低于此值（约为最常用字的千万分之一）算生僻字；码表权重见 tasks-stroke.gradle.kts。
local COMMON = 1e-7

return function(input, env)
  local exact, partial, rare = {}, {}, {}
  local count = 0
  local rest = nil
  for cand in input:iter() do
    if count >= LIMIT then
      rest = rest or {}
      rest[#rest + 1] = cand
      break
    end
    count = count + 1
    if cand.type == "completion" then
      -- 未完成的字质量减了 1，加回来就是字频。
      local freq = cand.quality + 1
      if freq >= COMMON then
        partial[#partial + 1] = { cand = cand, freq = freq, order = #partial }
      else
        rare[#rare + 1] = cand
      end
    elseif cand.type == "table" and cand.quality < COMMON then
      rare[#rare + 1] = cand
    else
      exact[#exact + 1] = cand
    end
  end
  table.sort(partial, function(a, b)
    if a.freq ~= b.freq then return a.freq > b.freq end
    return a.order < b.order
  end)
  local top = partial[1] and partial[1].freq or 0
  local minor, odd = {}, {}
  for _, cand in ipairs(exact) do
    if cand.quality >= top * MINOR then yield(cand)
    elseif cand.type == "table" and cand.quality < ODD then odd[#odd + 1] = cand
    else minor[#minor + 1] = cand end
  end
  for i, item in ipairs(partial) do
    if i == MINOR_AFTER + 1 then
      for _, cand in ipairs(minor) do yield(cand) end
      minor = {}
    end
    if i == ODD_AFTER + 1 then
      for _, cand in ipairs(odd) do yield(cand) end
      odd = {}
    end
    yield(item.cand)
  end
  for _, cand in ipairs(minor) do yield(cand) end
  for _, cand in ipairs(odd) do yield(cand) end
  for _, cand in ipairs(rare) do yield(cand) end
  if rest then
    for _, cand in ipairs(rest) do yield(cand) end
    for cand in input:iter() do yield(cand) end
  end
end
