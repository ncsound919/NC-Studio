# One-shot rebuild of SettingsDiagnostics.kt: extracts the complete
# SettingsSectionCard and SessionHealthCard definitions (full brace walk) from
# the intact clean-check copy and combines them with Section7Card.
import os
import re

CLEAN = r"C:\Users\tap45\Downloads\Pulse Daw\Pulse-Daw-main\pulse-clean-check\app\src\main\java\com\example\ui\SettingsScreen.kt"
OUT_DIR = r"app\src\main\java\com\example\ui"

clean = open(CLEAN, encoding="utf-8").read()
lines = clean.splitlines()


def find_fun(marker):
    """@Composable + fun...def block with a real brace-depth walk."""
    idx = next(i for i, l in enumerate(lines) if l.strip().startswith(marker))
    annotations = []
    a = idx - 1
    while a >= 0 and (lines[a].strip().startswith("@") or lines[a].strip().startswith("/*") is False and lines[a].strip() == ""):
        if lines[a].strip().startswith("@"):
            annotations.append(lines[a])
        a -= 1
    # collect doc comment too
    doc_start = a
    while doc_start >= 0 and (lines[doc_start].strip().startswith("*") or lines[doc_start].strip().startswith("/**") or lines[doc_start].strip().startswith("*/")):
        doc_start -= 1
    # walk braces from the def line, counting only braces seen after first '{'
    depth = 0
    opened = False
    k = idx
    while k < len(lines):
        lt = lines[k]
        for ch in lt:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    break
        if opened and depth == 0:
            break
        k += 1
    block_lines = annotations[::-1] + lines[idx:k + 1]
    return "\n".join(block_lines), k


settings_card, sc_end = find_fun("private fun SettingsSectionCard(")
session_health, sh_end = find_fun("private fun SessionHealthCard(")
settings_card = settings_card.replace("private fun SettingsSectionCard", "internal fun SettingsSectionCard")
session_health = session_health.replace("private fun SessionHealthCard", "internal fun SessionHealthCard")
assert "content()" in settings_card, "settings card def incomplete"
assert session_health.strip().endswith("}"), "session health def incomplete: " + session_health[-80:]
assert "formatOneDecimal" not in settings_card

# Section 7 body from the clean file (call at block 7 -> content lambda)
starts = [i for i, l in enumerate(lines) if l.strip().startswith("SettingsSectionCard(")]
s, j, k = None, None, None
s7s = starts[6]
j = s7s
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
s7 = "\n".join(lines[j + 1:k])
assert "SessionHealthCard" in s7 and "onRunDspTests" in s7

clean_imports = sorted({l for l in clean.splitlines() if l.startswith("import ")})
exclude = ("import androidx.activity.compose.BackHandler", "import android.content.Intent")
clean_imports = [i for i in clean_imports if not i.startswith(exclude)]

s7_gate = ["    if (!uiState.presentationMode) {"] + ["    " + l for l in s7.splitlines()] + ["    }"]
section7 = ["@Composable", "internal fun Section7Card(uiState: EngineUiState, settings: DawSettings, onRunDspTests: () -> Unit) {"] + s7_gate + ["}"]

out = ["package com.example.ui", ""]
out += clean_imports
out += [
    "",
    "/** Shared card wrapper for the settings sections (internal: reused by every SettingsSection*.kt file). */",
]
out += settings_card.splitlines()
out += [
    "",
    "/** DSP diagnostics card (Section 7 of the settings screen); hidden in presentation mode. */",
]
out += section7
out += [
    "",
    "/** Engine health summary card, rendered inside the DSP diagnostics section. */",
]
out += session_health.splitlines()
open(os.path.join(OUT_DIR, "SettingsDiagnostics.kt"), "w", encoding="utf-8", newline="\r\n").write("\n".join(out) + "\n")
print("rebuilt SettingsDiagnostics.kt:", len(out), "lines")
