# Bundled reader fonts (#992)

These fonts ship inside the APK to give readers dyslexia-friendly typography
options for the chapter-reading surface, available fully offline.

| Family | Files (`res/font/`) | License | Source |
|--------|---------------------|---------|--------|
| OpenDyslexic | `opendyslexic_regular.otf`, `opendyslexic_bold.otf` | SIL OFL 1.1 (`OFL-OpenDyslexic.txt`) | https://github.com/antijingoist/opendyslexic |
| Atkinson Hyperlegible | `atkinson_hyperlegible_regular.ttf`, `atkinson_hyperlegible_bold.ttf` | SIL OFL 1.1 (`OFL-AtkinsonHyperlegible.txt`) | Braille Institute / Google Fonts |

The SIL Open Font License requires the license text to travel with the font
binaries, which is why the `OFL-*.txt` files live here next to the module.
The XML font families that reference these binaries are
`res/font/family_opendyslexic.xml` and `res/font/family_atkinson_hyperlegible.xml`.
