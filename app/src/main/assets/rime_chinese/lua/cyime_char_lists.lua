--[[
  CyIME：仓颉、速成、笔画的码表按字形收字，日本的字（沢 伝）、韩国自造的字（乫）、粤语字（佢 嘅）都在里面。
  这三类 11-20 各有一个开关（设置 › 中文输入「日文汉字」「韩文汉字」「粤语字」，默认关；他：是什么就叫什么，
  仓颉、注音都要一样）。关着的那类字不出候选，含这些字的词也不出；开着时照码表原样出。
  字表和拼音那边是同一份（构建时生成的 cyime_japanese.txt、cyime_korean.txt、cyime_cantonese.txt，只取单字）。
  三个都开着时不读文件、原样放过。
--]]
local LISTS = {
  { option = "cyime_japanese_kanji", file = "/cyime_japanese.txt" },
  { option = "cyime_korean_hanja", file = "/cyime_korean.txt" },
  { option = "cyime_cantonese_chars", file = "/cyime_cantonese.txt" },
}

-- One table of a few hundred string keys per list (no table per character), loaded once per Lua state.
local function load(list)
  if list.set then return list.set end
  local set = {}
  list.set = set
  local path = list.file
  local f = io.open(rime_api.get_shared_data_dir() .. path, "r") or io.open(rime_api.get_user_data_dir() .. path, "r")
  if not f then return set end
  for line in f:lines() do
    local text = line:match("^([^\t]+)\t")
    if text and utf8.len(text) == 1 then set[text] = true end
  end
  f:close()
  return set
end

local function filter(input, env)
  local ctx = env.engine.context
  local hidden = {}
  for _, list in ipairs(LISTS) do
    if not ctx:get_option(list.option) then hidden[#hidden + 1] = load(list) end
  end
  for cand in input:iter() do
    local keep = true
    if #hidden > 0 then
      for _, cp in utf8.codes(cand.text) do
        if cp >= 0x3400 then
          local ch = utf8.char(cp)
          for _, set in ipairs(hidden) do
            if set[ch] then keep = false; break end
          end
          if not keep then break end
        end
      end
    end
    if keep then yield(cand) end
  end
end

return filter
