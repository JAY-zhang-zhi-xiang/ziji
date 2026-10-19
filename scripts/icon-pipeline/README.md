# 图标流水线 · Lucide → Android VectorDrawable

本项目 App 的图标全部来自开源库 [Lucide](https://lucide.dev)（**ISC 许可证**，可自由商用，仅需保留版权声明）。

## 为什么不用 emoji / Unicode 字符

改造前的做法有两类问题：

| 做法 | 问题 |
| --- | --- |
| 导航用几何字符 `▣ ◌ ◫ ♙` | 各 ROM 字体不同，同一图标在不同手机上"长相"差异极大，甚至显示成方框 |
| 分类用 emoji `🍜 🎮 🛍` | 各平台 emoji 字形不一，且彩色卡通风格与整体的暖灰 + 赤陶克制审美冲突 |

改用内置矢量图后：描边粗细统一、圆角一致、颜色由代码 tint 控制，且**离线可用**（符合本地账本定位）。

## 脚本说明

| 文件 | 作用 |
| --- | --- |
| `lucide-to-vector.py` | 从 Lucide CDN 批量拉取 SVG 并转成 Android VectorDrawable |
| `extract-svg.py` | 从已生成的 VectorDrawable 反解出 SVG 路径，供预览页复用 |
| `make-icons-preview.py` | 生成图标总览预览页（`icons-preview.html`） |
| `patch-ui-preview.py` | 把 `ui-preview.html` 里的 emoji/字符替换为真实 SVG |
| `svg_map.json` | 图标名 → 内联 SVG 的映射缓存 |

## 如何新增一个图标

1. 在 [lucide.dev/icons](https://lucide.dev/icons) 找到合适图标，记下名称（如 `coffee`）。
2. 编辑 `lucide-to-vector.py` 里的映射表（`EXPENSE` / `INCOME` / `NAV`），加入 `"ic_xxx": "coffee"`。
3. 重新生成全部图标：

```bash
python scripts/icon-pipeline/lucide-to-vector.py android-companion/src/main/res/drawable
```

4. 在 `SettingsActivity.java` 里用 `iconView("ic_xxx", 颜色, 尺寸)` 引用。

## 在代码中使用

```java
// 图标 + 着色（颜色由代码控制，不依赖主题属性）
ImageView icon = iconView("ic_bell", COLOR_PRIMARY, 17);

// 按钮前置图标
Button button = primaryButton("开启通知读取权限", "ic_bell");
```

取资源 id 走 `drawableId(name)`（内部用 `getResources().getIdentifier`），
好处是加图标不用改 `R.drawable` 引用，坏处是名称拼错时**不会编译报错**——
所以新增图标后务必跑一次构建，并在预览页里目视确认。

## 技术要点

- **转换**：Lucide 的 SVG 混用 `<path> <circle> <line> <rect>`，脚本会把基本图形换算成等价的 `pathData`。
- **着色**：资源里 `strokeColor` 用黑色占位，实际颜色由 `ImageView.setColorFilter` 或 `Drawable.setColorFilter` 控制。
- **不要加 `android:tint="?attr/colorControlNormal"`**：该属性来自 AppCompat 主题，本项目使用原生 `Activity`，会导致 AAPT 编译失败（`resource attr/colorControlNormal not found`）。

## 许可

Lucide 采用 ISC 许可证。每个生成的资源文件头部已保留版权声明：

```
<!-- Lucide Icons (ISC License) - https://lucide.dev -->
```
