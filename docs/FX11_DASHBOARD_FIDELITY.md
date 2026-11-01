# Retained dashboard design alignment (2026-10-28)

## Scope

Classic (`REPLICA`) is explicitly excluded at the user's request. Its dial, cover-derived
palette, music rendering, vehicle indicators and persisted settings are unchanged.
OEM overlay, ordinary CarPlay, audio, FX11 decoding and wheel-key behavior are unchanged.

The retained styles are Midnight, Panorama, Track (night/day), Polar (night),
Obsidian (night/day) and Starfield (night/day). Previously rejected minimal ring variants
are not reintroduced. Shared status/footer content and 1.0–1.5× icon scaling remain.

## Reference mapping

The original generated PNGs are stored outside the repository. Their identifiers below
refer to the original files, not new generated substitutes:

| Style | Reference PNG | Alignment |
| --- | --- | --- |
| Midnight | `exec-fe966b57-914a-4d8c-a1a9-8973f9ff6d91.png` | Open cyan arcs with brighter, thinner ticks; map-only center page remains wide. |
| Panorama | `exec-42b3f3dc-43cb-490f-912d-94bec1866b45.png` | Large left speed, compact right media/tire card, guidance below the shared header. Duplicate lamp row removed. |
| Track night | `exec-57b4c4af-4a16-43e0-9fe7-e260dd1d131e.png` | Diagonal layered panels, horizontal RPM strip, wide italic speed, angled music panel. |
| Track day | `exec-c35d1a44-8734-476a-972a-7fcbd453efad.png` | Light layered surfaces with restrained orange edges. |
| Polar | `exec-aef55142-9f1b-44ba-bf28-6c2552d037eb.png` | Metallic bezel, inner ring and cyan active sector. |
| Obsidian night | `exec-ab4a49a7-16c0-45dd-b531-bd4bf6dbc5e0.png` | Left half-arc panel, right cover/RPM sections, divided central guidance card. |
| Obsidian day | `exec-047ec799-a962-4e08-a6b9-32b42c3b12fb.png` | Light panels, dark type and teal active arc. |
| Starfield night | `exec-a327242d-3a5f-4775-b63c-a8321fdeae06.png` | Purple-gray star field continuing into thin-ring dials. |
| Starfield day | `exec-bc0f7c50-5b7e-4f88-85ac-93bcea07379b.png` | Lavender-gray background/dials, subtle stars and dark foreground. |

The originals have a different aspect ratio from the target 1920×532 panel. Circles
remain circular; center space and cards are adapted to the target rather than stretching
the whole reference. Later accepted behavior overrides old mockup details: no extra
front-fog lamp, no invented telemetry, no mock music controls/timestamps in compact
cards, and the existing shared anchored bars. This is a geometry/material alignment,
not a claim that every pixel equals the original generated artwork.

## Rendering and verification

`DashboardConceptRenderer` rasterizes static backgrounds/materials once per selected
style/appearance; tick marks and top/bottom surfaces use cached `Picture`s. The two
raster layers plus the optional map-edge mask are bounded to 12,257,280 bytes at
1920×532, with only the active style retained. Leaving a concept releases those references
without recycling bitmaps still used by an Android display list. Speed, RPM, cover and
live text remain dynamic. Vehicle updates do not rebuild static surfaces. Polar/Starfield ticks
are drawn above the active sector; Obsidian's active arc is drawn above its base stroke.

`DashboardFidelityTest` exports actual Android Canvas frames to
`common/build/reports/dashboard-fidelity/`, covering every supported appearance and page.
Its simplified road illustration and sample cover exist only in test code; neither is
packaged in the app. Navigation frames exercise the same transparent map window used
by the live video surface. No route means no navigation panel or arrival panel.

`DashboardConceptsTest` covers matching opaque top/bottom foreground pixels for all concept styles,
all supported appearances, and six icon scales. `NativeDashboardUiTest` retains the
Classic behavior/geometry/color regressions and verifies that Panorama has only one
lamp strip. `DashboardRenderPerformanceTest` retains Classic caching checks.

## Lighting pass

`DashboardSurfaceLighting` recreates the material layers procedurally, with no reference
screenshots embedded in production:

- Track: local orange light sources with falloff, an embossed lower texture, dark speed
  plate, shallow upper facet and an offset edge beside the map. Daytime has restrained
  orange specular edges on warm gray surfaces.
- Polar: angle-dependent silver reflections, a separate dark groove, inner blue rim,
  dark inner well and radial cyan sector with a thin bright pointer core.
- Obsidian: diffuse upper-right illumination over a dark blue-black panel, subtle grain
  and edge gradient; mint active arc/navigation glyph.
- Starfield: sampled gray-purple/lavender surfaces, background glow and fine grain,
  a shorter speed pointer, subdued active sector and a darker daytime outline.
- Concept module surfaces now follow the material (black, warm gray or gray-purple).
  Icon geometry, state colors, text and scaling remain shared. The Classic bar path
  bypasses all concept-specific material code.
- Circular concepts blend the extreme video edges into their own background without
  changing the map window, cropping the center or recoloring the source CarPlay video.
- Midnight's existing open arc gains a faint local cyan halo; Panorama keeps the
  previous aligned layout.

`DashboardLightingTest` checks median RGB against measured blank reference patches,
directional metallic reflections, local rather than uniform edge glow, bounded memory,
cache release and an unobscured video center. Patch tolerances are **not** an overall
pixel-similarity percentage; dynamic data, font rasterization, reference aspect ratio and
procedurally recreated texture prevent an honest whole-screen identity claim.

Real CarPlay map colors, camera position, music artwork and vehicle values are supplied
at runtime and cannot be made identical to mockup data by drawing a screenshot over
them. On-car video composition and frame timing require device verification.

## Detail pass / v54 test package

- Polar restores denser minor ticks independently of Classic, a brighter cyan sector,
  an illuminated inner lip and the lighter speed numeral weight.
- Obsidian uses the original tilted half-arc (98° origin), left-aligned inset music,
  mint playback accents and a solid RPM progress band with major/minor graduations.
- Track restores wider speed numerals, larger inset title, and trapezoidal anchored
  module ends. Shared icon coordinates and scale preferences are unchanged.
- Starfield removes the inset playback line absent from the source image; the center
  music page still has real playback progress.
- Midnight restores a compact navigation card and separate route summary, with a
  fine map outline. Panorama's guidance receives the matching solid arrow treatment.
- DashboardGuidanceGlyph replaces only straight/left/right silhouettes; roundabouts,
  U-turns and unknown maneuvers keep the supplied semantic drawable. Classic and OEM
  guidance do not use this helper.

The v54 package is built from an isolated export of be74c435 plus the dashboard files
listed in its source-manifest.json. Concurrent, uncommitted climate-control work is
not included. Version 0.2.21-hud-test-v54-dashboard, code 85; local runtime assets are
selected explicitly via DIPLAY_AUTH_ASSETS_DIR and excluded from the source archive.


## Silhouette and placement pass / v55

- Track uses continuously blurred, locally illuminated edges recorded into cached software
  surfaces, plus a reflective bevel and warm embossed detail. No per-frame blur is added.
- Obsidian replaces the wide tilted ellipse with a traced tall asymmetric curve; ticks
  and live speed progress follow its measured length. Endpoint numerals remain clear.
- Starfield has brighter star cores, sparse halos and visible points in both dial faces;
  half-resolution rendering is checked so points survive panel scaling.
- Midnight and Panorama move time, outside temperature, range, AutoHold, parking brake
  and gear into top wings on either side of the unchanged lamp strip. Old bottom modules
  and duplicate gear labels are removed. Midnight's map expands to 1408×456; Panorama
  retains 1488×472 but shifts below the header. New video dimensions apply on reconnect.
- Classic remains unchanged. Tests verify header persistence across pages, 1.5× icons,
  lower map visibility, curve proportions and star visibility in addition to prior tests.

The v55 isolated export includes only the dashboard manifest files over be74c435,
version 0.2.21-hud-test-v55-dashboard (86). Authentication assets remain local and
are excluded from source exports. Real-car composition still needs device testing.


## Track material pass / v56

Only Track's cached material rendering changes from v55. The speed plate casts a
soft shadow onto a recessed substrate; a wide folded shoulder uses a cross-edge
reflection with longitudinal falloff. Staggered embossed cells have separately lit
lower lips and dark recesses, varied by a deterministic seed. Orange reflection
follows the music panel's rounded lower corner and long bevel instead of producing
yellow corner spots. Day rendering uses the same physical layers with softer light.
Blur is restricted to style/appearance cache construction; telemetry never rebuilds
these materials. The night RPM segments also regain their warm reflected core with a reusable gradient;
redline segments remain red. Layout, text, shared icons, other styles and Classic stay at v55.


## Track light beams and separate music face / v57

- The music face now has its own right sloping edge, smooth charcoal material and
  warm reflected corners. A separate embossed backing is exposed outside the face;
  the upper-right folded fascia fills the previously exposed video patch.
- Directional orange beams have tapered tails and localized bright cores on the
  speed plate's upper edge, diagonal and lower lip, and on both sides of the music
  face. Day mode uses reduced glow. These are recorded only into the material cache.
- Track RPM uses forty 7px strokes instead of sixty-four 4px strokes: 200 rpm per
  stroke, with the redline retained at 7,000 rpm. The night reflected core remains.
  Tests check unknown/zero, 1,600, 7,000 and 8,000 rpm, bar thickness and redline.
- Music content moves 8 design pixels left to clear its new right sloping edge.
  Other dashboards, shared status icons, Classic and vehicle/CarPlay behavior do not change.


## Track optical edge profile / v58

The v57 filled triangular beam and its duplicate soft bloom are removed. A sampled
cross-edge profile now separates a narrow pale-gold specular core, weaker incident
light, stronger reflected light and a low-energy tail. A second gradient modulates
intensity along the edge. Both shaders are composed into the existing static cache.
Music bevels are desaturated and their middle sections darkened; corners retain
thin localized highlights. Forty thicker RPM strokes from v57 remain unchanged.

The profile regression uses the source's horizontal highlight as a local criterion:
pale core, core/shoulder contrast, and asymmetric falloff. It is not an overall
pixel-similarity score. Classic, all other styles and vehicle/CarPlay code stay unchanged.

## v59 · 2026-10-29 approved layouts

- Midnight navigation page: compact circular speed with music below it; the navigation instruction and remaining trip summary stay over the map. Dedicated full music and tire pages remain available with the existing paging order.
- Panorama: music/tire card moved below digital speed, outside the video; removed the former right-hand arrival panel and combined its available distance/time into guidance. Route end still removes guidance entirely.
- Track: left cached material compressed horizontally to 60% of v58's width, retaining the independent right music enclosure. Live text is repositioned without horizontal compression. RPM remains 40 strokes, 200 rpm each, with a 7,000 rpm redline. Map viewport expanded with the released space.
- Polar: reusable radial glint shader adds endpoint reflections at both inner and outer rims of both gauges; no per-frame blur or bitmap generation.
- Obsidian: left speed card 372→300 units wide, curve narrowed to80%; labels/ticks/progress/short bold live marker share arc-length coordinates. Unknown/invalid readings have no marker.
- Starfield: removed translucent filled progress sectors on both gauges, retaining uniform6px arcs and short5px terminal ticks in day/night.
- Classic rendering, normal CarPlay, vehicle subscriptions and OEM overlay unchanged. Top/bottom shared icon renderers unchanged.
- Reference previews are saved under outputs/dashboard-approved-v59-20261029. These are AI visual proposals; current app screenshots use schematic map/art fixtures and are not the generated designs pasted into the product.

## v64 圆盘高度

经典圆盘半径从 254 提升到 264，圆心仍为 y=266，532 高屏幕上下各保留 2 像素。极夜双环和星空的静态材质、刻度、动态弧线/高光、文字与音乐内容按同一比例放大，避免仅放大底图导致指示错位。中央上下状态模块保持原有大小。
Midnight 双圆盘音乐/胎压页在 60 高顶栏以下放大，圆心 y=296、半径 234；保留顶栏信息且不覆盖中央内容。Midnight 导航紧凑圆盘及全景/赛道/曜石等非完整双圆盘布局保持原有结构。
