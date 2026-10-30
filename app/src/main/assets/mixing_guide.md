# AGENT MIXING GUIDE — Gear, Consoles, Plugins & Pro Techniques
### A compartmentalized decision system for mixing songs

> This file is the **operational brain** for an agent mixing songs. It translates gear/console/plugin knowledge and pro-vs-novice techniques into actionable rules, chains, and decision trees. Pair with `MIXING_REFERENCE_BIBLE.md` for genre/artist deep dives.

---

## TABLE OF CONTENTS

1. Console & Gear Sound Profiles (SSL, Neve, API, etc.)
2. Classic Outboard Gear — Sound & Plugin Equivalents
3. Plugin Developer Techniques (High-End vs Novice)
4. Gain Staging for Analog-Modeled Plugins
5. Pro vs Novice Techniques (What Separates Them)
6. Agent Mixing Workflow (Step-by-Step Rules)
7. Genre-Specific Plugin Chains (Decision Matrix)
8. Common Mistakes & How to Avoid Them
9. Quick Reference: "Which Tool When" Cheat Sheet

---

# 1. CONSOLE & GEAR SOUND PROFILES

## 1.1 SSL 4000 E Series (1979)
- **Sound:** Punchy, aggressive, forward mids, in-your-face. Clean but colored. Defined 80s rock/pop.
- **Preamp:** Jensen transformer (early) → electronically balanced (later, less color). Modest gain, 20dB pad.
- **Dynamics:** Aphex/dbx VCA; program-dependent attack (3–30ms); FAST ATTACK 3ms; soft-knee (Rev 4); logarithmic release. "Punchy response, aggressive compression, fast gating."
- **EQ:** 4-band parametric + filters. Revisions: Brown Knob (02, gritty/aggressive), Orange Knob (132, Pultec-inspired, broader/musical), Black Knob (242, ±18dB, 18dB/oct HPF, the standard).
- **Mix bus:** SSL Bus Compressor (glue, 2:1–4:1, slow attack, auto release).
- **Plugins:** SSL 4K E (official), Waves SSL 4000 Collection, Slate VMR (FG-S), UAD SSL 4000 E, Plugin Alliance SSL 4K.
- **Use for:** Rock, pop, hip-hop — when you want punch, aggression, forward mids. Drums especially.

## 1.2 SSL 4000 G Series
- **Sound:** Refined, tight, focused detail, smoother than E. "Pink Knob" '292 EQ. More polished/versatile. Hi-fi clarity.
- **Preamp:** Redesigned, cleaner, lower noise, more headroom.
- **Dynamics:** Tighter, more focused transients; still familiar SSL punch.
- **EQ:** '292 "Pink Knob" — variable Q on mids, HMF×3 / LMF÷3 switches extend mid bands to spectrum edges. Shelving or bell on HF/LF.
- **Plugins:** SSL 4K G (official), Waves SSL 4000, Slate VMR (FG-G).
- **Use for:** Pop, R&B, hip-hop — when you want clarity and polish without E's aggression.

## 1.3 SSL 9000 Series (J/K)
- **Sound:** Clean, smooth, hi-fi, transparent, immersive. Deep extended low-end, shimmering highs. "Biggest mixes ever."
- **EQ:** Smooth, open, minimal phase shift; cleaner than G/E. Variable Q on mids; shelving/bell selectable on HF/LF.
- **Use for:** Pop, R&B, orchestral, film scores — when you want transparency and width. Mix bus for glue without color.

## 1.4 SSL ORIGIN / AWS / Duality (Modern)
- **Sound:** PureDrive preamps — switchable clean (SuperAnalogue) or warm/harmonically rich/driven. '242 black knob EQ. Bus compressor in center section.
- **Use for:** Modern hybrid workflows; clean or driven tones on demand.

## 1.5 Neve 1073 (1970s)
- **Sound:** Warm, rich, musical, thick low-mids, smooth highs. Class A. "Alive — not because it distorted, but because it distorted in exactly the right way."
- **Preamp:** Class A, transformer-coupled (Marinair). Gain up to 80dB. Warm, thick, harmonic saturation.
- **EQ:** 3-band (HF shelf 10/12/16kHz, mid peak 0.36/0.7/1.6/3.2/4.8/7.2kHz, LF shelf 35/60/110/220Hz) + HPF. Fixed frequencies, broad Q.
- **Character:** Thickens, warms, adds "expensive" low-mid weight. Saturates gracefully.
- **Plugins:** UAD Neve 1073, Waves Scheps 73, Slate FG-N, Plugin Alliance (Lindell, etc.), AMS Neve official.
- **Use for:** Vocals, bass, guitars, drums — when you want warmth, thickness, musicality. The "go-to" for making things sound expensive.

## 1.6 Neve 8078 / 8068 (1970s)
- **Sound:** Even warmer/thicker than 1073; "vintage Neve" — the D'Angelo Voodoo sound. Neve VR added recall.
- **Character:** Deep, round, saturated; less headroom than SSL; bass gets "big and open and round" but can get messy.
- **Use for:** Bass, drums, full mixes — when you want maximum warmth and saturation. Neve 8078 for "vintage Neve" vibe.

## 1.7 Neve VR (1980s)
- **Sound:** Neve warmth + SSL-like recall/automation. "Better sounding console than SSL, but didn't know how to get around on it" (Bob Power). Low headroom in mix bus; high end not "sweet" — work hard to avoid harshness.
- **Use for:** When you want Neve tone with automation; mixing Dilla-style hip-hop.

## 1.8 API (Automated Processes Inc.)
- **Sound:** Punchy, forward, "American" sound. Proportional Q EQ (boost = wider, cut = narrower). 2520 op-amp. Aggressive but musical.
- **Character:** Mid-forward, punchy, slightly gritty. Great for rock/drums.
- **Plugins:** UAD API 2500/550/560, Waves API Collection, Slate FG-A.
- **Use for:** Drums, rock, when you want punch and forward mids without SSL's aggression.

## 1.9 Trident (A-Range, 80B)
- **Sound:** Bright, airy, musical. "British" sound. Trident A-Range = silky highs, musical mids.
- **Plugins:** UAD Trident, Waves, Plugin Alliance.
- **Use for:** When you want air and brightness; vocals, guitars.

## 1.10 Harrison / MCI
- **Sound:** Clean, transparent, punchy. MCI JH24 (tape machine) used on Dilla sessions.
- **Use for:** Clean tracking; punchy drums.

---

# 2. CLASSIC OUTBOARD GEAR — SOUND & PLUGIN EQUIVALENTS

## 2.1 Universal Audio 1176 (FET Compressor)
- **Sound:** Fast, aggressive, punchy. Adds "edge" (dynamically and tonally). Harmonic distortion. All-buttons-in = "British mode" (explosive, distorted).
- **Specs:** Attack 20µs–800µs (fastest in class); release 50ms–1.1s; ratios 4:1, 8:1, 12:1, 20:1; all-buttons-in = ~∞:1 with unique distortion.
- **Use for:** Vocals (edge + presence), drums (punch), bass (tightness), guitars (aggression). Fast transients.
- **Chain:** 1176 → LA-2A (edge then smooth). Or 1176 alone for aggressive.
- **Plugins:** UAD 1176 (Rev A/E/LN), Waves CLA-76, Slate FG-116, Plugin Alliance (Lindell, etc.).

## 2.2 Teletronix LA-2A (Optical Compressor)
- **Sound:** Smooth, warm, gentle, musical. Tube + T4 optical cell. Slow attack (~10ms), program-dependent release. Adds warmth and "glue."
- **Specs:** Attack ~10ms, release 40ms–15s (program-dependent); peak reduction + gain; no ratio control (fixed ~3:1 soft knee).
- **Use for:** Vocals (smooth leveling), bass (warmth), acoustic guitar, mastering. Gentle, musical compression.
- **Chain:** 1176 → LA-2A (edge then smooth) or LA-2A alone for gentle.
- **Plugins:** UAD LA-2A (Silver/Gold), Waves CLA-2A, Slate FG-2A.

## 2.3 Fairchild 670 (Tube Compressor)
- **Sound:** Lush, warm, expensive. Tube + transformer. Lat/Vert (M/S) mode. Adds "size" and "air." Very slow (attack ~0.2ms–0.8s, release 0.3–25s). Used on vocals, mastering, mix bus.
- **Use for:** Vocals (lushness), mix bus (glue + size), mastering. Expensive sound.
- **Plugins:** UAD Fairchild 670/660, Waves PuigChild, Slate FG-MU (not exact), Plugin Alliance.

## 2.4 Pultec EQP-1A (Passive EQ)
- **Sound:** Musical, smooth, "Pultec trick" (boost and cut same frequency = resonant peak + dip). Low-end boost (30/60/100Hz) adds weight without mud; high boost (8/10/12/16kHz) adds air.
- **Trick:** Boost 60Hz + Cut 60Hz simultaneously = resonant bump at ~60Hz with dip below = tight, punchy low end.
- **Use for:** Kick, bass (low-end weight), vocals (air), mix bus (polish). The "expensive" EQ.
- **Plugins:** UAD Pultec, Waves PuigTec, Slate FG-P (not exact), Plugin Alliance.

## 2.5 dbx 160 (VCA Compressor)
- **Sound:** Punchy, aggressive, "dbx sound." Over-easy (soft knee). Great on drums, bass. Adds punch and tightness.
- **Specs:** Over-easy (soft knee); ratio 1:1 to ∞:1; fast attack/release (program-dependent).
- **Use for:** Drums (punch), bass (tightness), guitars. Hip-hop/R&B.
- **Plugins:** UAD dbx 160, Waves dbx 160, Plugin Alliance.

## 2.6 SSL Bus Compressor (G-Series)
- **Sound:** Glue, punch, "SSL mix bus sound." 2:1–10:1, auto release. Tightens low end, adds punch. The "mix bus compressor."
- **Use for:** Mix bus (glue), drum bus (punch). 2–4dB GR max.
- **Plugins:** SSL Bus Compressor (official), Waves SSL Bus Comp, UAD SSL Bus Comp, Slate (not exact).

## 2.7 Neve 1073 Preamp/EQ (see 1.5)
- **Use for:** Everything — the universal "make it sound expensive" tool.

## 2.8 EMT 140 Plate Reverb
- **Sound:** Lush, dense, smooth plate reverb. The "vocal reverb" of Motown/70s.
- **Use for:** Vocals, snare, guitars. 1.0–2.5s decay.
- **Plugins:** UAD EMT 140, Waves (not exact), Valhalla Plate.

## 2.9 EMT 250 Digital Reverb
- **Sound:** Early digital reverb; lush, metallic, "80s reverb." Used on countless records.
- **Plugins:** UAD EMT 250.

## 2.10 Lexicon 224 / 480L
- **Sound:** Lush, dense digital reverb. "The Lexicon sound" — large halls, plates. 80s/90s staple.
- **Plugins:** UAD Lexicon 224/480L, Waves (H-Reverb), Valhalla.

## 2.11 AMS RMX16
- **Sound:** Early digital reverb; "NonLin" (gated) reverb. The 80s snare sound.
- **Plugins:** UAD AMS RMX16.

## 2.12 Eventide H3000 (Harmonizer)
- **Sound:** Pitch shifting, harmonizing, modulation. The "Eventide sound."
- **Plugins:** Eventide (official), UAD.

## 2.13 Roland Space Echo (RE-201)
- **Sound:** Tape echo + spring reverb. Warm, warbly, vintage.
- **Plugins:** UAD, Waves, Soundtoys EchoBoy.

## 2.14 Tape Machines (Studer A800, Ampex ATR-102)
- **Sound:** Warmth, saturation, compression, "tape sound." Studer = cleaner; Ampex = warmer.
- **Plugins:** UAD Studer A800/Ampex ATR-102, Waves (J37, Kramer), Slate (Virtual Tape Machines).

---

# 3. PLUGIN DEVELOPER TECHNIQUES (HIGH-END vs NOVICE)

## 3.1 How High-End Plugins Are Made

**Analog modeling techniques (used by UAD, Waves, Slate, Plugin Alliance, etc.):**

1. **Circuit modeling (physical modeling):** Model individual components (transistors, capacitors, resistors, transformers, tubes) and their nonlinear behavior. UAD and Slate use this for "component-level" accuracy.
2. **Behavioral modeling:** Model the input/output relationship of the device (black-box). Easier but less accurate for edge cases.
3. **Convolution (for reverb):** Capture impulse responses of real spaces/gear. Used for reverbs and some EQs.
4. **Dynamic convolution:** Model nonlinear gear by capturing responses at multiple levels. Used by some high-end emulations.
5. **Oversampling:** Process at higher sample rates internally to reduce aliasing from nonlinear processing. High-end plugins use 2x–8x oversampling.
6. **Saturation modeling:** Model harmonic distortion (THD) at different drive levels, including even/odd harmonics, intermodulation distortion.
7. **Transformer modeling:** Model frequency-dependent saturation, phase shift, and low-end behavior of transformers.
8. **Tube modeling:** Model plate voltage, bias, and dynamic behavior of tubes (not just static waveshaping).
9. **VCA modeling:** Model control voltage, attack/release curves, and feedforward/feedback topology (SSL vs dbx vs 1176).
10. **Component tolerance:** Model real-world component variation (±5–20%) for "unit-to-unit" variation.

**What separates high-end from novice plugin development:**

| Aspect | High-End (UAD, Slate, Waves, PA) | Novice / Cheap |
|---|---|---|
| Modeling depth | Component-level or dynamic convolution | Static waveshaping / simple EQ |
| Nonlinearity | Level-dependent, frequency-dependent, dynamic | Fixed curve |
| Oversampling | 2x–8x internal | None or 2x |
| Transformer/tube modeling | Yes, with phase behavior | No |
| Noise floor | Modeled (optional) | No |
| Latency | Reported accurately (PDC) | Sometimes wrong |
| CPU efficiency | Optimized (SIMD, multithreading) | Inefficient |
| Gain staging | Modeled (input/output levels matter) | Ignored |
| Presets | Musical, genre-specific | Generic |

## 3.2 What High-End Mix Engineers Do Differently (vs Novices)

**Gain staging:**
- Pros: Set input levels so plugins operate in their "sweet spot" (often -18dBFS to -12dBFS for analog-modeled plugins). Use trim/gain before plugins.
- Novices: Slam plugins with full-scale signals; ignore input level.

**EQ:**
- Pros: Subtractive first (cut mud, resonance); boost only what's needed; use narrow cuts, broad boosts; EQ in context (not solo); reference.
- Novices: Boost everything; wide boosts; EQ in solo; "smiley face" EQ.

**Compression:**
- Pros: Set attack/release by feel and source; use slow attack for punch, fast for control; parallel compression; don't over-compress; gain-match (bypass test).
- Novices: Crush everything; fast attack kills transients; no gain matching; "louder = better."

**Reverb/Delay:**
- Pros: Pre-delay for clarity; EQ reverb (HPF/LPF); send-based (not insert); short for drums, long for vocals; mono reverb for focus.
- Novices: Insert reverb on every track; long tails on everything; no pre-delay; muddy lows in reverb.

**Panning/Stereo:**
- Pros: Keep low end mono; pan by frequency (highs wide, lows center); use mid-side; check mono compatibility.
- Novices: Pan everything wide; ignore mono; phase issues.

**Mix bus:**
- Pros: Glue compression (2–4dB GR); subtle saturation; reference; check translation.
- Novices: Slam the mix bus; over-limit; no reference.

**Workflow:**
- Pros: Gain stage first; balance faders first (no plugins); reference tracks; take breaks; check on multiple systems; automate.
- Novices: Plugins first; no reference; mix loud; no breaks; no automation.

**Monitoring:**
- Pros: Mix at low volume; check mono; use multiple systems; calibrate room.
- Novices: Mix loud; single system; uncalibrated room.

**Decision-making:**
- Pros: Serve the song; less is more; if it doesn't need it, don't do it; mute test (if you can't hear it, remove it).
- Novices: Process every track; more plugins = better; never mute.

---

# 4. GAIN STAGING FOR ANALOG-MODELED PLUGINS

## 4.1 The Golden Rule
- Analog-modeled plugins (UAD, Waves, Slate, PA) are designed to operate at **analog line level: -18dBFS to -12dBFS** (0VU = -18dBFS for most, -12dBFS for some).
- **If you feed them +0dBFS, they will distort/saturate more than intended** (or sound broken).
- **If you feed them -30dBFS, they will be too clean/quiet.**

## 4.2 How to Gain Stage
1. **Set track fader to 0 (unity).**
2. **Insert a gain/trim plugin first** (or use clip gain) to set the signal to -18dBFS to -12dBFS (peak or RMS depending on plugin).
3. **Process with analog-modeled plugin** — it now operates in its sweet spot.
4. **Gain-match the output** to the input level (bypass test) so you hear the tonal change, not the level change.
5. **Adjust fader** for mix balance.

## 4.3 Plugin-Specific Notes
- **UAD:** Most UAD plugins model analog gain staging. Feed -18dBFS for "0VU." Use input level knob on plugin.
- **Waves (SSL, API, etc.):** Similar — use input trim. Some (like SSL E-Channel) have input gain.
- **Slate VMR:** Modules have input/output; use VMR's trim or gain staging module.
- **Plugin Alliance:** Varies by plugin; check manual. Many use -18dBFS reference.
- **Softube:** Console 1 models gain staging per channel.

## 4.4 Digital (Non-Analog) Plugins
- **EQ (FabFilter Pro-Q, etc.):** Can handle any level; gain staging less critical but good practice.
- **Compressors (digital):** Less level-dependent; but still gain-match for A/B.
- **Reverbs/Delays:** Level into reverb matters (too hot = distorted reverb); aim for -18 to -12dBFS into reverb.

---

# 5. PRO vs NOVICE TECHNIQUES (DETAILED)

## 5.1 EQ Techniques

**Pro:**
- Cut first, boost second. Remove mud (200–400Hz), boxiness (400–800Hz), harshness (2–5kHz) before boosting.
- Narrow cuts (Q 4–8) for resonances; broad boosts (Q 0.5–1.5) for tone.
- EQ in context (full mix), not solo (solo lies).
- Reference: compare to a commercial track in the same genre.
- HPF everything except kick/bass/sub (30–100Hz depending on source).
- Use mid-side EQ: cut lows in sides, boost presence in center.
- Dynamic EQ for resonances that only appear at certain levels.

**Novice:**
- Boost everything; wide boosts; EQ in solo; "smiley face."
- No HPF; mud accumulates.
- Boost to fix problems instead of cutting.
- No reference; drift over time.

## 5.2 Compression Techniques

**Pro:**
- Set attack by transient need: slow attack (30–50ms) for punch, fast (1–5ms) for control.
- Release: medium (50–150ms) or auto; match to tempo (release between beats).
- Ratio: 2:1–4:1 for gentle; 6:1–10:1 for aggressive; ∞:1 for limiting.
- Gain-match: bypass test — should sound same level but better.
- Parallel compression: blend dry (transients) + wet (body).
- Sidechain: duck bass to kick, vocals to music.
- Multiband for targeted control (low-mid mud, high-mid harshness).

**Novice:**
- Fast attack on everything (kills transients).
- Too much GR (10dB+ on everything).
- No gain matching (louder = "better").
- No parallel/sidechain.
- Over-compress mix bus.

## 5.3 Reverb & Delay Techniques

**Pro:**
- Send-based (aux), not insert (except special effects).
- Pre-delay 20–40ms (vocals) for clarity; 0–10ms (drums) for glue.
- EQ reverb: HPF 100–300Hz, LPF 4–8kHz (remove mud and harshness).
- Short reverb for drums (0.6–1.2s), longer for vocals (1.5–2.5s), hall for gospel/strings.
- Mono reverb for focus; stereo for width.
- Delay: filtered (HPF/LPF), feedback 20–30%, sync to tempo (1/8, 1/4, 1/16 dotted).
- Slapback 80–120ms for rock vocals.
- Duck reverb to vocal (sidechain) for clarity.

**Novice:**
- Insert reverb on every track.
- Long tails on everything.
- No pre-delay; no EQ on reverb.
- Delay too loud/long; no filtering.

## 5.4 Stereo & Panning Techniques

**Pro:**
- Low end (kick, bass, sub, 808) mono below 100–120Hz.
- Pan by frequency: highs wide, lows center.
- Double-track guitars L/R for width (not just panning one).
- Mid-side processing: boost sides for width, cut lows in sides.
- Check mono compatibility (sum to mono; no phase cancellation).
- Use stereo correlation meter; aim for +0.3 to +0.8 (not +1.0, not negative).

**Novice:**
- Pan everything wide (including bass).
- Ignore mono compatibility.
- No mid-side; no correlation check.
- Phase issues from haas delay or stereo widening.

## 5.5 Mix Bus Techniques

**Pro:**
- Glue compression: 2:1–4:1, slow attack (30ms), auto release, 2–4dB GR.
- Subtle saturation (tape, +1–2dB drive).
- Gentle EQ (HPF 30Hz, ±0.5dB shelves).
- Limiter for reference only (true peak -1dBTP); remove before final master.
- Reference track on muted channel, level-matched.
- Check translation (phone, car, earbuds, small speaker).

**Novice:**
- Slam mix bus (10dB+ GR).
- Over-limit (crushed dynamics).
- No reference; no translation check.
- Mix bus as "fix everything" (should be subtle).

## 5.6 Workflow Techniques

**Pro:**
1. Gain stage all tracks (-18 to -12dBFS).
2. Balance faders (no plugins) — get the mix 80% there with faders only.
3. Add EQ (subtractive first).
4. Add compression (gentle, gain-matched).
5. Add effects (reverb/delay sends).
6. Add saturation/color.
7. Mix bus processing (glue).
8. Automate (volume rides, filter sweeps, throws).
9. Reference check (level-matched A/B).
10. Translation check (multiple systems).
11. Take breaks (10–15 min every 45–60 min).
12. Final polish (mute test, mono check, true peak).

**Novice:**
- Plugins first (before fader balance).
- No reference; no breaks; no translation check.
- Mix loud; ear fatigue.
- No automation.

## 5.7 Monitoring Techniques

**Pro:**
- Mix at low volume (conversational level) — reveals balance issues.
- Check at multiple volumes (quiet, normal, loud).
- Use multiple systems (nearfields, headphones, small speaker, car).
- Calibrate room (SPL meter, reference level ~75–80dB SPL).
- Check mono regularly.

**Novice:**
- Mix loud (ear fatigue, "loudness bias").
- Single system.
- Uncalibrated room.
- Never check mono.

## 5.8 Decision-Making Techniques

**Pro:**
- "Serve the song" — every decision supports the emotion.
- "Less is more" — if it doesn't need it, don't do it.
- Mute test: if you can't hear it when muted, remove it.
- "Does this make it better?" — if no, undo.
- Reference constantly (level-matched).
- Trust ears over eyes (don't mix with your eyes on the meters).
- Take breaks; fresh ears catch problems.

**Novice:**
- "More plugins = better."
- Process every track.
- Never mute or remove.
- Trust meters over ears.
- No breaks; ear fatigue.

---

# 6. AGENT MIXING WORKFLOW (STEP-BY-STEP RULES)

## Rule 1: Identify Genre & Reference
- Determine genre from user request or audio analysis.
- Select reference track from `MIXING_REFERENCE_BIBLE.md` (one per genre).
- Import reference on muted track; level-match to mix (LUFS).

## Rule 2: Gain Stage
- Set all tracks to -18 to -12dBFS (peak/RMS).
- Use trim/gain plugin or clip gain before any processing.
- Analog-modeled plugins operate in sweet spot.

## Rule 3: Fader Balance (No Plugins)
- Balance all faders to get mix 80% there.
- Use panning for width; keep low end center.
- Check on small speaker.

## Rule 4: Subtractive EQ
- HPF everything except kick/bass/sub (30–100Hz).
- Cut mud (200–400Hz), boxiness (400–800Hz), harshness (2–5kHz).
- Narrow cuts for resonances; broad for tone.
- EQ in context (full mix), not solo.

## Rule 5: Compression (Gentle, Gain-Matched)
- Add compression where needed (vocals, drums, bass).
- Slow attack for punch (30–50ms); fast for control (1–5ms).
- Release: medium or auto; match tempo.
- Ratio 2:1–4:1; gain-match (bypass test).
- Parallel compression on drums/vocals.
- Sidechain: bass to kick, vocals to music.

## Rule 6: Effects (Sends)
- Reverb/delay on aux sends (not inserts).
- Pre-delay for clarity; EQ reverb (HPF/LPF).
- Short for drums, longer for vocals.
- Delay: filtered, synced, feedback 20–30%.

## Rule 7: Saturation & Color
- Tape on mix bus (subtle, +1–2dB).
- Tube on vocals/bass; transistor on drums.
- Analog-modeled channel strips for color (Neve for warmth, SSL for punch).

## Rule 8: Mix Bus Processing
- Glue compression (2:1–4:1, slow attack, auto release, 2–4dB GR).
- Gentle EQ (HPF 30Hz, ±0.5dB shelves).
- Subtle saturation.
- Limiter for reference only (remove before final).

## Rule 9: Automation
- Volume rides on vocals.
- Filter sweeps on builds.
- Reverb throws on phrase ends.
- Mute/unmute for dynamics.

## Rule 10: Reference & Translation Check
- A/B with reference (level-matched).
- Check mono, phone, earbuds, car, small speaker.
- Take breaks; fresh ears.
- Final polish: mute test, mono check, true peak.

---

# 7. GENRE-SPECIFIC PLUGIN CHAINS (DECISION MATRIX)

## 7.1 Classic Hip-Hop / G-Funk
- **Drums:** 1176 (punch) → SSL E (punch) → parallel comp. Kick: Pultec (low boost). Snare: plate reverb.
- **Bass:** Neve 1073 (warmth) → SSL bus comp (tighten). Sub mono.
- **Synths:** Juno (chorus II) → delay (filtered) → reverb.
- **Vocals:** Neve 1073 (warmth) → 1176 (edge) → LA-2A (smooth) → de-ess → air shelf. Doubles panned.
- **Mix bus:** SSL bus comp (glue) → tape saturation → limiter (ref).

## 7.2 Pop (Modern)
- **Drums:** SSL E/G (punch) → parallel comp → samples layered. Kick: Pultec (low boost) + click. Snare: plate reverb.
- **Bass:** Neve 1073 (warmth) → sidechain to kick → SSL bus comp.
- **Vocals:** Neve 1073 → 1176 → LA-2A → de-ess → air shelf 10-12kHz. Doubles/harmonies panned 80%.
- **Guitars:** Neve 1073 → SSL E (carve 1-3kHz) → pan L/R.
- **Mix bus:** SSL bus comp (2:1, 2-3dB) → tape → limiter (ref).

## 7.3 House (Classic)
- **Kick:** SSL E (punch) → Pultec (60Hz boost) → sidechain (to bass).
- **Bass:** Neve 1073 (organ) → sidechain to kick (4:1, fast) → mono.
- **Hats:** HPF 300Hz → boost 8-10kHz → pan.
- **Snare/Clap:** plate reverb (1.0s) → HPF 200Hz.
- **Pads:** Juno (chorus II) → sidechain to kick → reverb/delay.
- **Vocals:** chopped → filter → delay → reverb.
- **Mix bus:** SSL bus comp → tape → limiter (ref).

## 7.4 Trap / Drill
- **808:** Neve 1073 (warmth) → saturation (heavy) → sidechain to kick → mono sub + distortion layer (panned L/R).
- **Kick:** SSL E (punch) → layered with 808 transient.
- **Hats:** HPF 200Hz → boost 8-12kHz → pitch variation → pan.
- **Snare:** layered (acoustic + clap + rim) → plate reverb (short) → boost 5kHz.
- **Vocals:** hard Auto-Tune → 1176 → LA-2A → de-ess → reverb/delay (heavy).
- **Mix bus:** SSL bus comp → tape → limiter (ref).

## 7.5 R&B
- **Drums:** soft kick (controlled) → crisp snare (room reverb) → hi-hat rolls.
- **Bass:** Neve 1073 (warm, round) → sidechain to kick → SSL bus comp.
- **Vocals:** Neve 1073 → 1176 → LA-2A → de-ess → air shelf. Harmonies stacked (3-part) panned wide.
- **Keys/Pads:** Neve 1073 → reverb/delay → sidechain to vocals.
- **Mix bus:** SSL bus comp (gentle) → tape → limiter (ref).

## 7.6 Gospel
- **Vocals:** Lead forward/dry (Neve 1073 → LA-2A, minimal comp). Choir wide/deep (hall reverb 2-3s, mid-side).
- **Drums:** live feel (SSL E, gentle) → parallel comp.
- **Keys:** organ/piano (Neve 1073 warmth) → reverb.
- **Bass:** warm (Neve 1073) → SSL bus comp.
- **Mix bus:** SSL bus comp (gentle, preserve dynamics) → tape → limiter (ref).

## 7.7 Rock
- **Drums:** SSL E (punch) → parallel comp → samples layered. Toms cleaned. Snare: plate reverb.
- **Guitars:** Neve 1073 → SSL E (carve 1-3kHz) → pan L/R → parallel distortion.
- **Bass:** Neve 1073 (tight) → saturation → SSL bus comp.
- **Vocals:** Neve 1073 → 1176 → LA-2A → de-ess → slapback delay (optional).
- **Mix bus:** SSL bus comp → tape → limiter (ref).

---

# 8. COMMON MISTAKES & HOW TO AVOID THEM

## 8.1 Gain Staging
- **Mistake:** Feeding analog-modeled plugins +0dBFS (distortion/broken sound).
- **Fix:** Gain stage to -18 to -12dBFS before analog-modeled plugins.

## 8.2 Over-Processing
- **Mistake:** Plugins on every track; "more = better."
- **Fix:** Mute test; if you can't hear it, remove it. Less is more.

## 8.3 EQ in Solo
- **Mistake:** EQing in solo; sounds great solo, terrible in mix.
- **Fix:** EQ in context (full mix). Check mute/unmute.

## 8.4 Over-Compression
- **Mistake:** Fast attack on everything; 10dB+ GR; no gain matching.
- **Fix:** Slow attack for punch; gain-match; bypass test; parallel comp.

## 8.5 No Reference
- **Mistake:** Mixing in a vacuum; drift over time.
- **Fix:** Level-matched reference track; A/B constantly.

## 8.6 Mixing Too Loud
- **Mistake:** Ear fatigue; "loudness bias"; missed details.
- **Fix:** Mix at conversational level; take breaks; check at multiple volumes.

## 8.7 No Mono Check
- **Mistake:** Phase issues; mix collapses in mono.
- **Fix:** Sum to mono regularly; fix phase issues.

## 8.8 No Translation Check
- **Mistake:** Mix sounds great on studio monitors, terrible on phone/car.
- **Fix:** Check on phone, earbuds, car, small speaker.

## 8.9 Ignoring Low End
- **Mistake:** Mud accumulation; kick/bass fighting.
- **Fix:** HPF everything except kick/bass/sub; mono sub below 100-120Hz; sidechain.

## 8.10 Over-Limiting
- **Mistake:** Crushed dynamics; distorted master.
- **Fix:** Limiter for reference only; remove before final master; true peak -1dBTP.

---

# 9. QUICK REFERENCE: "WHICH TOOL WHEN" CHEAT SHEET

## By Goal

| Goal | Tool | Settings |
|---|---|---|
| Warmth / thickness | Neve 1073 | Preamp drive, EQ low-mid boost |
| Punch / aggression | SSL 4000 E | Fast attack, 4:1, forward mids |
| Clarity / polish | SSL 4000 G / 9000 | Variable Q, smooth highs |
| Glue (mix bus) | SSL Bus Comp | 2:1, slow attack, auto release, 2-4dB |
| Edge (vocals/drums) | 1176 | 4:1-8:1, fast attack, all-buttons-in for grit |
| Smooth (vocals/bass) | LA-2A | Peak reduction, gain, program-dependent |
| Lush (vocals/master) | Fairchild 670 | Slow, tube, Lat/Vert mode |
| Low-end weight | Pultec EQP-1A | Boost 60Hz + cut 60Hz (trick) |
| Punch (drums/bass) | dbx 160 | Over-easy, fast, 4:1-8:1 |
| Space (vocals) | EMT 140 Plate | 1.0-2.0s, pre-delay 20-40ms |
| Space (drums) | Room reverb | 0.6-1.2s, mono |
| Space (gospel/strings) | Hall reverb | 2.0-3.0s, stereo |
| Width (guitars/keys) | Chorus / Haas | 10-20ms delay, subtle |
| Air (vocals) | High shelf | 10-12kHz, +1-3dB |
| Presence (vocals) | Boost | 2-4kHz, +1-3dB |
| De-ess | De-esser | 5-8kHz, 2-4dB reduction |
| Mud removal | EQ cut | 200-400Hz, -2 to -4dB |
| Boxiness removal | EQ cut | 400-800Hz, -2 to -4dB |
| Harshness removal | EQ cut | 2-5kHz, -2 to -4dB |
| Sub-bass control | HPF + sidechain | HPF 25-30Hz, sidechain to kick |
| Tape warmth | Tape plugin | +1-2dB drive, subtle |
| Stereo width | Mid-side EQ | Boost sides 5-10kHz, cut lows in sides |

## By Source

| Source | Primary Chain | Alternative |
|---|---|---|
| Kick | SSL E → Pultec (low) → parallel comp | dbx 160 → SSL bus comp |
| Snare | SSL E → plate reverb (short) | 1176 → LA-2A |
| Hi-Hats | HPF → boost 8-12kHz → pan | SSL E (gentle) |
| 808/Sub | Neve 1073 → saturation → sidechain | dbx 160 → SSL bus comp |
| Bass | Neve 1073 → sidechain → SSL bus comp | 1176 → LA-2A |
| Vocals (lead) | Neve 1073 → 1176 → LA-2A → de-ess → air | SSL E → Fairchild 670 |
| Vocals (harmony) | Neve 1073 → LA-2A → pan wide | SSL E → reverb |
| Guitars | Neve 1073 → SSL E (carve) → pan L/R | API 550 → parallel dist |
| Keys/Pads | Neve 1073 → reverb/delay → sidechain | SSL 9000 → chorus |
| Synths (Juno) | Chorus II → delay → reverb → sidechain | — |
| Synths (DX7) | Chorus → delay → reverb (wet) | — |

---

*End of Agent Mixing Guide. Use as the operational decision system for mixing songs. Pair with MIXING_REFERENCE_BIBLE.md for genre/artist deep dives and reference tracks.*
