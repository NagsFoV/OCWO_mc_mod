# Overly Complicated Water Overhaul (OCWO)

OCWO is a **modified fork of [PhysEx-Reborn](https://github.com/Sasai-Kudasai-BM/PhysEx-Reborn)**
by **Sasai_Kudasai_BM**, distributed under the **GNU General Public License v3** (see `LICENSE`).

## Changes from PhysEx-Reborn

- Renamed (mod id `ocwo`, package `io.github.nagsfov.ocwo`) and reorganised as a multi-loader project
  (`common`, `fabric`, `neoforge`) based on the MultiLoader-Template layout.
- Ported from Minecraft 1.21.11 to Minecraft 26.3 (Java 25).
- Removed the dependency on the author's `skdslib` library; the few helpers used were rewritten
  (`util/FastMath`, `util/ArrayUtils`, `util/Vec2F`) and the boot config now uses Gson.
- Removed Lombok.
- Fabric API calls (data attachments, game rules, flammability, fluid attributes, tooltips) moved behind
  a platform interface (`IPlatformHelper`), implemented in the `fabric` module.
- Items store a vanilla `Fluid` instead of Fabric's `FluidVariant`.
- 26.x port: `LiquidBlockRenderer` → `FluidRenderer`, `FarmBlock` → `FarmlandBlock` (`turnToDirt` →
  `turnToBaseBlock`), `BlockAndTintGetter` and `CriteriaTriggers` moved; the falling-block and piston mixins now
  target `setBlockAndUpdate` (vanilla no longer calls `setBlock(pos, state, 3)` there); Fabric's flammability and
  tooltip APIs were renamed.

## OCWO additions

- Bucket pick-up modes (game rule `ocwo:bucket_area_pickup`) and a conservation fix in `FluidUtils.pickUpFluid`.
- Water pressure (hydraulic head, game rule `ocwo:pressure_search_limit`), see `06-pression.md` in the design docs.
