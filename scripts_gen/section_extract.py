# One-shot recovery/split script: rebuilds the seven settings section files from
# the intact pulse-clean-check copy and rewrites the surviving SettingsScreen shell.
# Run once from the Pulse-Daw-main/Pulse-Daw-main project root with: python scripts_gen/section_extract.py
import re
import os

CLEAN = r"C:\Users\tap45\Downloads\Pulse Daw\Pulse-Daw-main\pulse-clean-check\app\src\main\java\com\example\ui\SettingsScreen.kt"
SHELL = r"app\src\main\java\com\example\ui\SettingsScreen.kt"
OUT_DIR = r"app\src\main\java\com\example\ui"

clean = open(CLEAN, encoding="utf-8").read()
lines = clean.splitlines()

starts = [i for i, l in enumerate(lines) if l.strip().startswith("SettingsSectionCard(")]
blocks = []
for s in starts:
    j = s
    while j < len(lines) and not lines[j].strip().startswith(") {"):
        j += 1
    depth = 1
    k = j + 1
    while k < len(lines):
        lt = lines[k].strip()
        depth += lt.count("{") - lt.count("}")
        if depth == 0:
            break
        k += 1
    blocks.append((s, j, k))

assert len(blocks) == 7, blocks

def body(idx):
    s, j, k = blocks[idx]
    return "\n".join(lines[j + 1:k])

s1, s2, s3, s4, s5, s6, s7 = [body(i) for i in range(7)]

assert "ui_hardware_buffer_size" in s1 and "latencyStep = 0" in s1
assert "onOpenUsbDialog" in s2
assert "ui_recording_count_in_pre_roll" in s3
assert "ui_master_metering_ballistics_standard" in s4
assert "ui_sonic_target_profile" in s5
assert "showClearCacheDialog = true" in s6 and "uiState.cacheSizeBytes" in s6
assert "SessionHealthCard" in s7 and "onRunDspTests" in s7

# Section 1: the latency-trigger lambda becomes the callback the shell already passes.
s1 = s1.replace(
    "onClick = {\n                            latencyStep = 0\n                            showLatencyDialog = true\n                        },",
    "onClick = onCalibrateLatencyRequest,",
)
assert "latencyStep" not in s1 and "showLatencyDialog" not in s1

# Section 6: the click that used to flip the inline dialog flag becomes onClearCacheRequest.
s6 = s6.replace("onClick = { showClearCacheDialog = true },", "onClick = onClearCacheRequest,")
assert "showClearCacheDialog" not in s6

clean_imports = [l for l in lines if l.startswith("import ")]

def imports_for(text):
    used = []
    for imp in clean_imports:
        sym = imp.split("import ", 1)[1].split(".")[-1]
        if sym == "*":
            continue
        if re.search(r"\b" + re.escape(sym) + r"\b", text):
            used.append(imp)
        # theme package colors: map via identifier already matched above
    return sorted(set(used))

def composable_imports(text):
    imps = imports_for(text)
    imps.append("import androidx.compose.runtime.Composable")
    return sorted(set(imps))

def emit(fname, doc, composables):
    out = ["package com.example.ui", ""]
    all_imports = []
    for c in composables:
        all_imports.extend(c.pop("imports", []))
    # union imports sorted
    out += sorted(set(all_imports))
    out += ["", "/**", " * " + doc, " */"]
    for c in composables:
        out.append("")
        out += c["body"]
    open(os.path.join(OUT_DIR, fname), "w", encoding="utf-8", newline="\r\n").write("\n".join(out) + "\n")
    print("wrote %s" % fname)

def section_composable(fun, title, icon, icon_color, body_text, params, imports):
    hdr = ["@Composable", "internal fun %s(%s) {" % (fun, params)]
    card = [
        "    SettingsSectionCard(",
        "        icon = %s," % icon,
        "        iconColor = %s," % icon_color,
        '        title = "%s"' % title,
        "    ) {",
    ]
    b = list(hdr) + card + body_text.splitlines() + ["    }", "}"]
    return {"imports": imports, "body": b}

specs = [
    ("SettingsSectionEngine.kt", "Engine & buffer size settings card (Section 1 of the settings screen).",
     "Section1Card", "Audio Engine & Buffer Size", "Icons.Default.Speed", "NeonCyan",
     "settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit, onCalibrateLatencyRequest: () -> Unit", s1),
    ("SettingsSectionUsb.kt", "USB routing settings card (Section 2 of the settings screen).",
     "Section2Card", "USB-C Audio Interface Routing", "Icons.Default.Usb", "ElectricLime",
     "settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit, onOpenUsbDialog: () -> Unit", s2),
    ("SettingsSectionRecording.kt", "Recording & monitoring settings card (Section 3 of the settings screen).",
     "Section3Card", "Recording & Monitoring", "Icons.Default.Mic", "ClipRed",
     "settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit", s3),
    ("SettingsSectionMetering.kt", "Metering & visuals settings card (Section 4 of the settings screen).",
     "Section4Card", "Metering & Visuals", "Icons.Default.Tune", "StudioPurple",
     "settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit", s4),
    ("SettingsSectionAi.kt", "AI mix assistant settings card (Section 5 of the settings screen).",
     "Section5Card", "AI Mixing Studio Assistant", "Icons.Default.AutoAwesome", "StudioAmber",
     "settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit", s5),
    ("SettingsSectionStorage.kt", "Storage & crash recovery settings card (Section 6 of the settings screen).",
     "Section6Card", "Storage & Crash Recovery", "Icons.Default.Save", "ElectricLime",
     "settings: DawSettings, uiState: EngineUiState, onUpdateSettings: (DawSettings) -> Unit, onClearCacheRequest: () -> Unit", s6),
]

for fname, doc, fun, title, icon, icon_color, params, btext in specs:
    emit(fname, doc, [section_composable(fun, title, icon, icon_color, btext, params, composable_imports(btext))])

def find_fun(marker):
    idx = next(i for i, l in enumerate(lines) if l.strip().startswith(marker))
    start = idx - 1  # @Composable line
    depth = 0
    k = idx
    while k < len(lines):
        lt = lines[k].strip()
        depth += lt.count("{") - lt.count("}")
        if k > idx and depth == 0:
            break
        k += 1
    return "\n".join(lines[start:k + 1])

session_health = find_fun("private fun SessionHealthCard(").replace(
    "private fun SessionHealthCard", "internal fun SessionHealthCard")
settings_card = find_fun("private fun SettingsSectionCard(").replace(
    "private fun SettingsSectionCard", "internal fun SettingsSectionCard")

# Section 7 keeps its presentationMode gate inside the composable.
s7_gate = ["    if (!uiState.presentationMode) {"] + ["    " + l for l in s7.splitlines()] + ["    }"]
section7_body = [
    "@Composable",
    "internal fun Section7Card(uiState: EngineUiState, settings: DawSettings, onRunDspTests: () -> Unit) {",
] + s7_gate + ["}"]

emit("SettingsDiagnostics.kt",
     "DSP diagnostics card (Section 7) plus the shared section card wrapper and engine health card.",
     [{"imports": composable_imports(settings_card) + composable_imports(session_health) + composable_imports("\n".join(section7_body)),
       "body": settings_card.splitlines()},
      {"imports": [], "body": section7_body},
      {"imports": [], "body": session_health.splitlines()}])

# ---- shell rewrite -------------------------------------------------------
shell = open(SHELL, encoding="utf-8").read()
slines = shell.splitlines()

def shell_del_block(marker):
    idx = next(i for i, l in enumerate(slines) if l.strip().startswith(marker))
    start = idx - 1  # @Composable
    depth = 0
    k = idx
    while k < len(slines):
        lt = slines[k].strip()
        depth += lt.count("{") - lt.count("}")
        if k > idx and depth == 0:
            break
        k += 1
    return start, k

a, b = shell_del_block("private fun SettingsSectionCard(")
c, d = shell_del_block("private fun SessionHealthCard(")
new_shell = slines[:a] + slines[b + 1:d + 1] + slines[d + 1:]

# Re-add the About & Version card (was inline before the cached Spacer(32)).
anchor = next(i for i, l in enumerate(new_shell) if l.strip().startswith("Section7Card("))
about_start = next(i for i, l in enumerate(lines) if "About & Version Card" in l)
sp = next(i for i, l in enumerate(lines) if "Spacer(modifier = Modifier.height(32.dp))" in l)
about = lines[about_start:sp]
while about and not about[-1].strip():
    about.pop()
new_shell = new_shell[:anchor + 1] + about + new_shell[anchor + 1:]

open(SHELL, "w", encoding="utf-8", newline="\r\n").write("\n".join(new_shell) + "\n")
print("rewrote SettingsScreen.kt: %d lines" % len(new_shell))
