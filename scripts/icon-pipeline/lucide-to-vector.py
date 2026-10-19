#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Lucide 官方图标转成 Android VectorDrawable。

为什么需要转换：
- Lucide 输出的是标准 SVG（含 stroke/fill 等 CSS 属性 + 多段 path）。
- Android 只认 VectorDrawable，需要：
  1. 用 viewportWidth/Height 替代 viewBox
  2. 每个 <path> 补上 android:strokeColor / strokeWidth / strokeLineCap / strokeLineJoin
  3. stroke="currentColor" → 用 ?,0 占位，运行时由 setTint 上色
  4. 圆角/斜接等属性名映射到 android 命名空间
"""
import os
import re
import sys
import urllib.request

VERSION = "1.58.0"
BASE = f"https://unpkg.com/lucide-static@{VERSION}/icons/"

# ---- 分类图标映射（支出 20 个）----
EXPENSE = {
    "餐饮": "utensils-crossed",
    "娱乐": "gamepad-2",
    "购物": "shopping-bag",
    "穿搭": "shirt",
    "零食": "cookie",
    "交通": "bus-front",
    "日用": "spray-can",
    "人情": "gift",
    "宠物": "paw-print",
    "美容": "sparkles",
    "运动": "dumbbell",
    "服务": "droplets",
    "买菜": "carrot",
    "住房": "house",
    "爱车": "car",
    "红包": "hand-coins",
    "转账": "arrow-left-right",
    "教育": "graduation-cap",
    "虚拟": "dices",
    "烟酒": "wine",
}

# ---- 分类图标映射（收入 16 个）----
INCOME = {
    "工资": "briefcase-business",
    "兼职": "bike",
    "投资理财": "chart-line",
    "人情社交": "heart-handshake",
    "奖金补贴": "trophy",
    "报销": "receipt-text",
    "生意": "store",
    "卖二手": "tag",
    "生活费": "hand-coins",
    "中奖": "ticket",
    "收红包": "gift",
    "收转账": "arrow-down-left",
    "保险理赔": "shield-check",
    "退款": "rotate-ccw",
    "其他": "circle-ellipsis",
    "管理": "settings",
}

# ---- 导航与界面图标 ----
NAV = {
    "nav_list": "list",
    "nav_auto": "zap",
    "nav_stats": "chart-pie",
    "nav_me": "user-round",
    "add": "plus",
    "search": "search",
    "edit": "pencil",
    "delete": "trash-2",
    "backup": "download",
    "restore": "upload",
    "rules": "sliders-horizontal",
    "history": "history",
    "chevron": "chevron-down",
    "close": "x",
    "check": "check",
    "alert": "circle-alert",
    "info": "info",
    "wallet": "wallet",
    "calendar": "calendar-days",
    "bell": "bell",
    "eye": "eye",
    "shield": "shield-check",
    "battery": "battery-charging",
    "link": "link",
    "unlink": "unlink",
    "refresh": "refresh-cw",
    "upload": "upload",
    "settings": "settings-2",
}


def to_vector_drawable(name, svg_text, size_dp=24):
    """
    把 Lucide SVG 文本转成 VectorDrawable XML。

    Lucide 混用 <path> / <circle> / <line> / <rect> 等基本图形，
    VectorDrawable 只接受 pathData，所以这里把基本图形也换算成等价路径：
      circle → 两段圆弧
      line   → M...L...
      rect   → 四段直线（含圆角时用圆弧近似）
    """
    path_data = []

    for d in re.findall(r'<path\s+[^>]*d="([^"]+)"', svg_text):
        path_data.append(d.strip())

    for match in re.finditer(r'<circle\s+([^/>]+)/?>', svg_text):
        attrs = dict(re.findall(r'(\w+)="([^"]+)"', match.group(1)))
        cx, cy, r = float(attrs["cx"]), float(attrs["cy"]), float(attrs["r"])
        path_data.append(
            f"M{cx - r},{cy} "
            f"a{r},{r} 0 1,0 {2 * r},0 "
            f"a{r},{r} 0 1,0 {-2 * r},0 Z"
        )

    for match in re.finditer(r'<line\s+([^/>]+)/?>', svg_text):
        attrs = dict(re.findall(r'(\w+)="([^"]+)"', match.group(1)))
        path_data.append(f'M{attrs["x1"]},{attrs["y1"]} L{attrs["x2"]},{attrs["y2"]}')

    for match in re.finditer(r'<rect\s+([^/>]+)/?>', svg_text):
        attrs = dict(re.findall(r'(\w+)="([^"]+)"', match.group(1)))
        x, y = float(attrs["x"]), float(attrs["y"])
        w, h = float(attrs["width"]), float(attrs["height"])
        rx = float(attrs.get("rx", 0))
        if rx > 0:
            path_data.append(
                f"M{x + rx},{y} H{x + w - rx} A{rx},{rx} 0 0,1 {x + w},{y + rx} "
                f"V{y + h - rx} A{rx},{rx} 0 0,1 {x + w - rx},{y + h} "
                f"H{x + rx} A{rx},{rx} 0 0,1 {x},{y + h - rx} "
                f"V{y + rx} A{rx},{rx} 0 0,1 {x + rx},{y} Z"
            )
        else:
            path_data.append(f"M{x},{y} H{x + w} V{y + h} H{x} Z")

    if not path_data:
        return None

    width_match = re.search(r'stroke-width="([\d.]+)"', svg_text)
    stroke_width = width_match.group(1) if width_match else "2"

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Lucide Icons (ISC License) - https://lucide.dev -->',
        '<!-- 颜色由代码统一控制（ImageView.setColorFilter），故这里不设 android:tint，',
        '     也避免依赖 AppCompat 主题属性 ?attr/colorControlNormal。 -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{size_dp}dp"',
        f'    android:height="{size_dp}dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]
    for d in path_data:
        lines.append('    <path')
        lines.append(f'        android:pathData="{d}"')
        lines.append('        android:strokeColor="#FF000000"')
        lines.append(f'        android:strokeWidth="{stroke_width}"')
        lines.append('        android:strokeLineCap="round"')
        lines.append('        android:strokeLineJoin="round"')
        lines.append('        android:fillColor="#00000000" />')
    lines.append('</vector>')
    return "\n".join(lines) + "\n"


def fetch(icon_name):
    url = BASE + icon_name + ".svg"
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8")


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    mapping = {}
    for category, icon in EXPENSE.items():
        mapping[f"ic_cat_exp_{icon.replace('-', '_')}"] = icon
    for category, icon in INCOME.items():
        mapping[f"ic_cat_inc_{icon.replace('-', '_')}"] = icon
    for key, icon in NAV.items():
        mapping[f"ic_{key}"] = icon

    cache = {}
    ok, fail = 0, []
    for res_name, icon in sorted(mapping.items()):
        try:
            if icon not in cache:
                cache[icon] = fetch(icon)
            xml = to_vector_drawable(res_name, cache[icon])
            if xml is None:
                fail.append((res_name, icon, "no path"))
                continue
            with open(os.path.join(out_dir, res_name + ".xml"), "w", encoding="utf-8") as handle:
                handle.write(xml)
            ok += 1
        except Exception as error:
            fail.append((res_name, icon, str(error)))

    print(f"生成成功: {ok}")
    if fail:
        print(f"失败: {len(fail)}")
        for item in fail:
            print("  ", item)


if __name__ == "__main__":
    main()
