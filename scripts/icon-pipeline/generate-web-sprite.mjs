import { readFile, writeFile } from "node:fs/promises";

const mapPath = new URL("./svg_map.json", import.meta.url);
const outputPath = new URL("../../assets/icons.svg", import.meta.url);
const icons = JSON.parse(await readFile(mapPath, "utf8"));

const aliases = {
  ic_alert: ["icon-toast-error"],
  ic_cat_inc_circle_ellipsis: ["icon-cat-other"],
  ic_cat_exp_arrow_left_right: ["icon-transfer"],
  ic_check: ["icon-toast-success"],
  ic_delete: ["icon-trash"],
  ic_edit: ["icon-pencil"],
  ic_info: ["icon-toast-info"],
  ic_nav_auto: ["icon-bolt"]
};

function symbolId(key) {
  return `icon-${key.slice(3).replaceAll("_", "-")}`;
}

const symbols = [];
for (const [key, svg] of Object.entries(icons)) {
  const match = svg.match(/^<svg\b[^>]*\bviewBox="([^"]+)"[^>]*>([\s\S]*)<\/svg>$/);
  if (!match) throw new Error(`Invalid SVG entry: ${key}`);
  const ids = [symbolId(key), ...(aliases[key] || [])];
  for (const id of ids) {
    symbols.push(`  <symbol id="${id}" viewBox="${match[1]}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${match[2]}</symbol>`);
  }
}

const sprite = [
  '<svg xmlns="http://www.w3.org/2000/svg">',
  '  <!-- Lucide Icons (ISC License) - https://lucide.dev -->',
  ...symbols,
  '</svg>',
  ""
].join("\n");

await writeFile(outputPath, sprite, "utf8");
console.log(`Generated ${symbols.length} symbols in assets/icons.svg`);
