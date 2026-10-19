#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ui-preview.html 里的 emoji / Unicode 图标替换成真实 Lucide SVG。

只替换"图标位"（class="ico" 的徽章、class="ni" 的导航图标、fab 的加号），
正文里的中文和标点一律不动。
"""
import json
import re
import sys


def inline(svg, size):
    """
    调整内联 SVG 的尺寸，颜色交给 CSS 的 currentColor。

    注意：必须用 (?<!-) 负向断言排除 stroke-width / stroke-height 之类属性。
    否则 width="2"（stroke-width 的一部分）会被误改成 width="22"，
    结果 stroke-width="22" —— 描边粗达 22px，整个图标会糊成实心方块。
    """
    svg = re.sub(r'(?<![-\w])width="\d+"', f'width="{size}"', svg)
    svg = re.sub(r'(?<![-\w])height="\d+"', f'height="{size}"', svg)
    return svg


def main():
    map_path, html_path = sys.argv[1], sys.argv[2]
    data = json.load(open(map_path, encoding="utf-8"))
    src = open(html_path, encoding="utf-8").read()

    # ---- 1. 账单卡片的分类徽章：<div class="ico exp">🍜</div> ----
    badge_map = {
        "🍜": "ic_cat_exp_utensils_crossed",
        "🍓": "ic_cat_exp_cookie",
        "💼": "ic_cat_inc_briefcase_business",
        "🚌": "ic_cat_exp_bus_front",
        "🛍": "ic_cat_exp_shopping_bag",
        "🚗": "ic_cat_exp_car",
        "🏠": "ic_cat_exp_house",
        "🎮": "ic_cat_exp_gamepad_2",
    }

    def replace_badge(match):
        cls, emoji = match.group(1), match.group(2)
        name = badge_map.get(emoji)
        if not name or name not in data:
            return match.group(0)
        return f'<div class="ico {cls}">{inline(data[name], 22)}</div>'

    src, badge_count = re.subn(r'<div class="ico (exp|inc)">([^<]+)</div>', replace_badge, src)

    # ---- 2. 底部导航图标：<span class="ni">▣</span> ----
    nav_map = {"▣": "ic_nav_list", "◌": "ic_nav_auto", "◫": "ic_nav_stats", "♙": "ic_nav_me"}

    def replace_nav(match):
        glyph = match.group(1)
        name = nav_map.get(glyph)
        if not name or name not in data:
            return match.group(0)
        return f'<span class="ni">{inline(data[name], 21)}</span>'

    src, nav_count = re.subn(r'<span class="ni">([^<]+)</span>', replace_nav, src)

    # ---- 3. FAB 加号：<div class="fab">＋</div> ----
    def replace_fab(match):
        if "ic_add" not in data:
            return match.group(0)
        return f'<div class="fab">{inline(data["ic_add"], 24)}</div>'

    src, fab_count = re.subn(r'<div class="fab">[^<]+</div>', replace_fab, src)

    # ---- 4. 让 .ni / .fab 内的 SVG 跟随文字颜色 ----
    src = src.replace(
        ".nav-item .ni { font-size: 20px; color: var(--faint); line-height: 1; }",
        ".nav-item .ni { color: var(--faint); line-height: 1; display:flex; align-items:center; justify-content:center; }\n"
        "  .nav-item .ni svg { stroke: currentColor; }"
    )
    src = src.replace(
        ".fab { flex: 0 0 58px; height: 52px; margin: 0 10px; border-radius: 999px; background: var(--primary); color: #fff; font-size: 26px; display: flex; align-items: center; justify-content: center; box-shadow: var(--shadow-soft); }",
        ".fab { flex: 0 0 58px; height: 52px; margin: 0 10px; border-radius: 999px; background: var(--primary); color: #fff; display: flex; align-items: center; justify-content: center; box-shadow: var(--shadow-soft); }\n"
        "  .fab svg { stroke: #fff; }"
    )
    # 账单徽章里的 SVG 跟随 exp/inc 配色
    src = src.replace(
        ".entry .ico.exp { background: var(--expense-soft); }",
        ".entry .ico.exp { background: var(--expense-soft); color: var(--expense); }\n"
        "  .entry .ico.inc { background: var(--income-soft); color: var(--income); }\n"
        "  .entry .ico svg { stroke: currentColor; }"
    )

    open(html_path, "w", encoding="utf-8").write(src)
    print(f"账单徽章替换: {badge_count}")
    print(f"导航图标替换: {nav_count}")
    print(f"FAB 图标替换: {fab_count}")


if __name__ == "__main__":
    main()
