-- 笔画方案的 * 通配：每个 * 代替一笔（横竖撇捺折任一），只找笔数相同的字，按字频排序。
-- 最多展开 125 种笔画组合（三个 *），再多就不查，免得打字卡顿。
local STROKES = { "h", "s", "p", "n", "z" }
local MAX_CODES = 125
-- Longest entry in the bundled stroke table has 52 strokes.
local MAX_CHARACTER_STROKES = 52

local M = {}

-- 编码默认显示字母 h s p n z（11-18）；设置里选了显示笔画时，方案补丁写回 translator/preedit_format，
-- lua 造的候选跟着换成笔画（#372）。分词号、通配符原样留着。
local STROKE_GLYPHS = { h = "⼀", s = "⼁", p = "⼃", n = "⼂", z = "乛" }
local glyphs = false
local function stroke_preedit(code)
  if not glyphs then return code end
  return (code:gsub("[hspnz]", STROKE_GLYPHS))
end

function M.init(env)
  glyphs = env.engine.schema.config:get_string("translator/preedit_format/@0") ~= nil
  env.mem = Memory(env.engine, env.engine.schema)
end

function M.fini(env)
  env.mem = nil
  env.word_lookup = nil
  collectgarbage()
end

-- Only expand a two-character reading when both parts are complete stroke codes.
-- Keep partial readings and wildcard substitutions that cannot form a character out of the sentence search.
local function two_characters(code, exact)
  local separator = code:find("'", 1, true)
  if separator then return exact(code:sub(1, separator - 1)) and exact(code:sub(separator + 1)) end
  for i = math.max(1, #code - MAX_CHARACTER_STROKES), math.min(#code - 1, MAX_CHARACTER_STROKES) do
    if exact(code:sub(1, i)) and exact(code:sub(i + 1)) then return true end
  end
  return false
end

function M.func(input, seg, env)
  if not input:find("*", 1, true) or not input:find("[^*]") then return end
  local codes = { "" }
  for ch in input:gmatch(".") do
    local options = ch == "*" and STROKES or { ch }
    local expanded = {}
    for _, prefix in ipairs(codes) do
      for _, option in ipairs(options) do expanded[#expanded + 1] = prefix .. option end
    end
    if #expanded > MAX_CODES then return end
    codes = expanded
  end
  local found, seen = {}, {}
  for _, code in ipairs(codes) do
    if env.mem:dict_lookup(code, false, 0) then
      for entry in env.mem:iter_dict() do
        if not seen[entry.text] then
          seen[entry.text] = true
          found[#found + 1] = { text = entry.text, weight = entry.weight, order = #found }
        end
      end
    end
  end
  table.sort(found, function(a, b)
    if a.weight ~= b.weight then return a.weight > b.weight end
    return a.order < b.order
  end)
  for _, item in ipairs(found) do
    local cand = Candidate("stroke", seg.start, seg._end, item.text, "")
    cand.preedit = stroke_preedit(input)
    yield(cand)
  end
  -- The character table cannot look up a whole word by its concatenated strokes.
  -- Keep the existing one-character results; if they run out, let the same Rime sentence decoder resolve the word.
  if #found > 0 or #input < 2 or #input > 2 * MAX_CHARACTER_STROKES + 1 then return end
  local known = {}
  local function exact(code)
    if known[code] == nil then known[code] = code ~= "" and env.mem:dict_lookup(code, false, 1) end
    return known[code]
  end
  for _, code in ipairs(codes) do
    if two_characters(code, exact) then
      env.word_lookup = env.word_lookup or Component.Translator(env.engine, "", "table_translator@stroke_word_lookup")
      local translation = env.word_lookup:query(code, seg)
      if translation then
        for cand in translation:iter() do
          if cand.type == "sentence" and cand._end == seg._end and utf8.len(cand.text) == 2 and not seen[cand.text] then
            seen[cand.text] = true
            local word = Candidate("stroke", seg.start, seg._end, cand.text, "")
            word.preedit = stroke_preedit(input)
            yield(word)
          end
          -- The native table translator offers its composed sentence first, followed by single characters.
          break
        end
      end
    end
  end
end

return M
