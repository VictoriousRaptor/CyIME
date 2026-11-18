# 双拼辅助码码表

`cyime_aux_zrm.dict.yaml`、`cyime_aux_flypy.dict.yaml` 由下面两张表改写成 Rime 码表（「字=码」换成「字<Tab>码」）：

| CyIME 文件 | 来源文件 | 说明 |
| --- | --- | --- |
| `cyime_aux_zrm.dict.yaml` | `aux_code/ZRM_Aux-code_4.3.txt` | 自然码辅助码（修改版，去冗余首码），原始来源 copperay/ZRM_Aux-code；原表中字已损坏的 143 行未收 |
| `cyime_aux_flypy.dict.yaml` | `aux_code/flypy_full.txt` | 小鹤形码（全码），社区整理 |

来源仓库：https://github.com/HowcanoeWang/rime-lua-aux-code ，提交 f9780033396f8998697c2a004d40bd543c46308b，MIT 许可，原文如下。

```
MIT License

Copyright (c) 2023 浩瀚猫

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
