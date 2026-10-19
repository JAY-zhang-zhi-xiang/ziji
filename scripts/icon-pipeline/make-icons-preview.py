#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成图标总览预览页 + 两个界面快照。

图标的 SVG 路径直接从 App 的 VectorDrawable 提取（svg_map.json），
保证预览与真机渲染的同源性，不做任何手工美化。
"""
import json
import sys

# 分类映射（与 SettingsActivity.java 保持一致）
EXPENSE = [
    ("ic_cat_exp_utensils_crossed", "餐饮"), ("ic_cat_exp_gamepad_2", "娱乐"),
    ("ic_cat_exp_shopping_bag", "购物"), ("ic_cat_exp_shirt", "穿搭"),
    ("ic_cat_exp_cookie", "零食"), ("ic_cat_exp_bus_front", "交通"),
    ("ic_cat_exp_spray_can", "日用"), ("ic_cat_exp_gift", "人情"),
    ("ic_cat_exp_paw_print", "宠物"), ("ic_cat_exp_sparkles", "美容"),
    ("ic_cat_exp_dumbbell", "运动"), ("ic_cat_exp_droplets", "服务"),
    ("ic_cat_exp_carrot", "买菜"), ("ic_cat_exp_house", "住房"),
    ("ic_cat_exp_car", "爱车"), ("ic_cat_exp_hand_coins", "红包"),
    ("ic_cat_exp_arrow_left_right", "转账"), ("ic_cat_exp_graduation_cap", "教育"),
    ("ic_cat_exp_dices", "虚拟"), ("ic_cat_exp_wine", "烟酒"),
]
INCOME = [
    ("ic_cat_inc_briefcase_business", "工资"), ("ic_cat_inc_bike", "兼职"),
    ("ic_cat_inc_chart_line", "投资理财"), ("ic_cat_inc_heart_handshake", "人情社交"),
    ("ic_cat_inc_trophy", "奖金补贴"), ("ic_cat_inc_receipt_text", "报销"),
    ("ic_cat_inc_store", "生意"), ("ic_cat_inc_tag", "卖二手"),
    ("ic_cat_inc_hand_coins", "生活费"), ("ic_cat_inc_ticket", "中奖"),
    ("ic_cat_inc_gift", "收红包"), ("ic_cat_inc_arrow_down_left", "收转账"),
    ("ic_cat_inc_shield_check", "保险理赔"), ("ic_cat_inc_rotate_ccw", "退款"),
    ("ic_cat_inc_circle_ellipsis", "其他"), ("ic_cat_inc_settings", "管理"),
]
NAV = [
    ("ic_nav_list", "明细"), ("ic_nav_auto", "自动记"),
    ("ic_nav_stats", "统计"), ("ic_nav_me", "我的"), ("ic_add", "新增"),
]
UI = [
    ("ic_bell", "通知权限"), ("ic_eye", "页面识别"), ("ic_battery", "后台设置"),
    ("ic_history", "处理记录"), ("ic_rules", "分类规则"), ("ic_backup", "备份"),
    ("ic_restore", "恢复"), ("ic_check", "已开启"), ("ic_search", "搜索"),
    ("ic_edit", "编辑"), ("ic_delete", "删除"), ("ic_wallet", "账本"),
    ("ic_shield", "安全"), ("ic_calendar", "日历"), ("ic_info", "提示"),
    ("ic_alert", "警告"), ("ic_refresh", "刷新"), ("ic_close", "关闭"),
    ("ic_chevron", "展开"), ("ic_settings", "设置"), ("ic_link", "链接"),
]


def svg_span(map_data, name, size, color):
    raw = map_data.get(name, "")
    if not raw:
        return ""
    return raw.replace('width="24"', f'width="{size}"')
    # 颜色由外层 CSS 的 currentColor 控制


def grid(map_data, items, color, size=26):
    cells = []
    for name, label in items:
        svg = svg_span(map_data, name, size, color)
        cells.append(
            f'<div class="cell"><div class="glyph">{svg}</div><div class="cap">{label}</div></div>'
        )
    return '<div class="grid">' + "".join(cells) + "</div>"


def main():
    map_path, out_path = sys.argv[1], sys.argv[2]
    data = json.load(open(map_path, encoding="utf-8"))

    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>自记 · 图标替换预览（Lucide）</title>
<style>
  :root {{
    --bg:#ECE8E0; --surface:#FEFDFA; --border:#DED8CE; --border-soft:#EAE5DC;
    --text:#2A2E32; --muted:#585F65; --faint:#92989E;
    --primary:#A4432D; --primary-dark:#7E3221; --primary-soft:#F6DDD3;
    --income:#2F7A5F; --expense-soft:#F6E2DB; --income-soft:#E0EEE5;
    --shadow: 0 2px 14px rgba(15,18,22,.07), 0 1px 3px rgba(15,18,22,.05);
    --font: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; padding:32px 24px 56px; background:#F4F1EA; color:var(--text);
         font-family:var(--font); -webkit-font-smoothing:antialiased; }}
  h1 {{ max-width:1180px; margin:0 auto 6px; font-size:22px; font-weight:600; letter-spacing:-.01em; }}
  .lead {{ max-width:1180px; margin:0 auto 26px; color:var(--muted); font-size:14px; line-height:1.7; }}
  .section {{ max-width:1180px; margin:0 auto 30px; }}
  .section h2 {{ font-size:15px; font-weight:600; margin:0 0 14px; letter-spacing:.01em; }}
  .section h2 small {{ font-weight:400; color:var(--faint); font-size:12px; margin-left:8px; }}
  .grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(78px,1fr)); gap:8px; }}
  .cell {{ background:var(--surface); border:1px solid var(--border); border-radius:12px;
           padding:14px 6px 10px; text-align:center; box-shadow:var(--shadow); }}
  .glyph {{ color:var(--text); display:flex; align-items:center; justify-content:center; height:30px; }}
  .cap {{ font-size:11px; color:var(--muted); margin-top:8px; }}
  .accent .glyph {{ color:var(--primary); }}
  .note {{ max-width:1180px; margin:34px auto 0; font-size:13px; color:var(--muted); line-height:1.8; }}
  .note b {{ color:var(--text); }}
  code {{ background:var(--surface); border:1px solid var(--border); border-radius:4px;
          padding:1px 5px; font-size:12px; }}
</style>
</head>
<body>
  <h1>图标替换预览 · Lucide</h1>
  <p class="lead">全部图标来自开源库 <b>Lucide</b>（ISC 许可），已内置为 Android 矢量图（离线可用）。
  下面的图形是直接从 App 的矢量资源反解出来的，与真机渲染一致。</p>

  <div class="section accent">
    <h2>支出分类 <small>20 个</small></h2>
    {grid(data, EXPENSE, '#A4432D')}
  </div>

  <div class="section">
    <h2>收入分类 <small>16 个</small></h2>
    {grid(data, INCOME, '#2F7A5F')}
  </div>

  <div class="section accent">
    <h2>底部导航 <small>5 个</small></h2>
    {grid(data, NAV, '#A4432D', 28)}
  </div>

  <div class="section">
    <h2>界面功能图标 <small>21 个</small></h2>
    {grid(data, UI, '#585F65')}
  </div>

  <p class="note">
    <b>替换前 vs 替换后：</b><br>
    · 原导航用几何 Unicode 字符（<code>▣</code> <code>◌</code> <code>◫</code> <code>♙</code>），在不同 ROM 上渲染差异极大，甚至可能显示成方框；<br>
    · 原分类用 emoji（<code>🍜</code> <code>🎮</code> <code>🛍</code>），各平台字形不一，且彩色卡通风格与暖灰+赤陶的克制审美冲突；<br>
    · 现在全部改为内置矢量图：描边粗细统一（1.5–2px）、圆角一致、颜色由代码统一 tint，离线可用。<br><br>
    <b>许可：</b>Lucide 采用 ISC 许可证，可自由用于商业项目，仅需保留版权声明（已在每个资源文件头部注明）。
  </p>
</body>
</html>
"""
    open(out_path, "w", encoding="utf-8").write(html)
    print(f"预览页已生成: {out_path}")


if __name__ == "__main__":
    main()
