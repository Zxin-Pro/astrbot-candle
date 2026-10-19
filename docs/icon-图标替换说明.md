# 图标替换说明

- 源图 `ui\assets\icon\source.png`（1254×1254）；产物 `ui\assets\icon\res\**` 全部由 `python tools\make-icons.py --src ui\assets\icon\source.png --out ui\assets\icon\res` 生成，幂等、同参数字节一致。
- 15 张 PNG：`mipmap-*/ic_launcher.png`（方形 48/72/96/144/192，不透明 RGB）、`ic_launcher_round.png`（圆形，内容 84% 居中，边缘延展补底不留方块缝）、`drawable-*/ic_foreground.png`（自适应前景 108/162/216/324/432，内容 72% 居中再右移 2.5%，保证 72dp 可见圆内不切到「烛」「心」）。
- 自适应背景 `drawable/ic_launcher_background.xml` = 纯色 `#FF1A0A12`（`<shape><solid>`，文件名/类型不变）；`_apkwork\res\drawable\ic_foreground.xml`（原矢量气泡）必须删掉，否则默认配置下它会压过 `drawable-*/ic_foreground.png`。
- `build-ui.ps1` 在 `apktool b` 之前注入（纯 ASCII、可重复跑；缺 `source.png` 或 `res` 时只打一行 skip）。
- 预览图：`out\icon-preview.png`（五档 × 白/深底）、`out\icon-preview-mask.png`（圆形 / 圆角方形遮罩）。
- 想进一步压体积可加 `--png-colors 256`：方形与前景转 8bit 调色板（圆图保持真彩，因为需要软边），15 张从 756KB 降到 398KB。
