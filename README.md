# JoeyOS data

Generated files the app downloads, rebuilt by the "Romhack index" workflow on master.
Don't edit by hand: each publish replaces this branch with a single commit.

- `ra-patches/<console>.json`: RetroAchievements romhacks by base ROM CRC32, from
  RetroAchievements' RAPatches repository and the No-Intro dats in libretro-database.
- `cache/formats.json`: what each RAPatches archive holds, by git blob sha, so a
  rebuild only reads new archives.
- `source-commit`: the RAPatches commit this was built from.
