#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从生成的 Android VectorDrawable 反向提取 pathData，
生成 HTML 内联 SVG 片段。

为什么这样做：预览页必须与 App 里真实的图标一致。
直接从同一批 XML 提取，避免"预览好看、实际不同"的偏差。
"""
import glob
import os
import re
import sys


def extract_paths(xml_path):
    """从 VectorDrawable 中取出所有 pathData 和 strokeWidth。"""
    src = open(xml_path, encoding="utf-8").read()
    paths = re.findall(r'android:pathData="([^"]+)"', src)
    width_match = re.search(r'android:strokeWidth="([\d.]+)"', src)
    stroke = width_match.group(1) if width_match else "2"
    return paths, stroke


def to_svg_inline(paths, stroke, size=24, color="currentColor"):
    """拼成内联 SVG（24x24 viewBox，与 Lucide 原生尺寸一致）。"""
    body = "".join(
        f'<path d="{d}" />' for d in paths
    )
    return (
        f'<svg viewBox="0 0 24 24" width="{size}" height="{size}" fill="none" '
        f'stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">{body}</svg>'
    )


def main():
    drawable_dir = sys.argv[1]
    out_file = sys.argv[2]

    icons = {}
    for path in sorted(glob.glob(os.path.join(drawable_dir, "ic_*.xml"))):
        name = os.path.basename(path)[:-4]
        if name == "ic_launcher_foreground":
            continue
        paths, stroke = extract_paths(path)
        icons[name] = to_svg_inline(paths, stroke)

    # 输出成 JSON 供预览页内联使用
    import json
    with open(out_file, "w", encoding="utf-8") as handle:
        json.dump(icons, handle, ensure_ascii=False, indent=1)
    print(f"提取 {len(icons)} 个图标的 SVG 路径 -> {out_file}")


if __name__ == "__main__":
    main()
