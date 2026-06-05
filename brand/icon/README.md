# Candela — App Icon Bundle

The **Voiceprint** mark: a five-bar voice equalizer whose tall center bar is a lit
candle. Reading made audible — light from the page. *Library Nocturne* palette
(warm gold/amber on near-black).

```
export/
├─ svg/                         Vector source (scales to any size)
│  ├─ candela-mark-rich.svg     Full-color mark + glow (transparent)
│  ├─ candela-mark-flat.svg     Two-tone gold, flat
│  ├─ candela-mark-line.svg     Single-weight line
│  ├─ candela-mark-mono.svg     One-color silhouette
│  ├─ candela-icon-full.svg     Composed 1024 icon (nocturne bg + mark)
│  ├─ candela-adaptive-foreground.svg
│  ├─ candela-adaptive-background.svg
│  └─ candela-adaptive-monochrome.svg
├─ android/
│  ├─ mipmap-mdpi … xxxhdpi/    ic_launcher.png + ic_launcher_round.png (48→192)
│  ├─ adaptive/                 ic_launcher_foreground / _background / _monochrome (432)
│  ├─ mipmap-anydpi-v26/        ic_launcher.xml + ic_launcher_round.xml
│  └─ play-store-512.png        Play Console listing icon
├─ ios/
│  ├─ AppIcon-1024.png          App Store (opaque, unrounded — iOS masks)
│  ├─ AppIcon-180.png  AppIcon-120.png
└─ web/
   ├─ favicon.svg  favicon-16/32/48.png
   ├─ apple-touch-icon.png  icon-192.png  icon-512.png  icon-maskable-512.png
   ├─ site.webmanifest  head-snippet.html
```

## Android
Drop the `mipmap-*` PNGs into `app/src/main/res/`. Put the three `adaptive/`
PNGs into your `mipmap-*` (or `drawable-nodpi/`) folders and keep the
`mipmap-anydpi-v26/*.xml` referencing them. The XML already wires
**foreground + background + monochrome** (the monochrome layer powers Android 13+
themed icons). Art sits inside the 66dp safe circle; the flame glow may bleed
past it — only the solid bars must stay inside.

## iOS
Use `AppIcon-1024.png` for the App Store / asset catalog. The icon is opaque and
square — iOS applies the rounding. (180/120 included for convenience.)

## Web / PWA
Paste `head-snippet.html` into `<head>`, ship the `web/` files at site root.
`icon-maskable-512.png` carries extra padding for Android/Chrome maskable mode.

## Palette
| Role | Hex |
|---|---|
| Flame core | `#FFF7DC` |
| Flame tip | `#FFE9A6` |
| Flame mid | `#FF9F38` |
| Gold | `#ECC97E` |
| Gold deep | `#C79443` |
| Wick / spine | `#7C5826` |
| Nocturne bg | `#14110D` → `#0A0810` |

Type: **Cormorant Garamond** (wordmark) · **Outfit** (UI).

All PNGs were rasterized from the SVG vector source — regenerate at any size from
`svg/`.
