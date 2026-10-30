# One-shot import fixer: replaces the import block of each generated settings
# section file with the full import superset from the intact clean-check copy.
# Unused imports are warnings only (no warningsAsErrors in app/build.gradle.kts).
import os

CLEAN = r"C:\Users\tap45\Downloads\Pulse Daw\Pulse-Daw-main\pulse-clean-check\app\src\main\java\com\example\ui\SettingsScreen.kt"
OUT_DIR = r"app\src\main\java\com\example\ui"

clean = open(CLEAN, encoding="utf-8").read()
clean_imports = sorted({l for l in clean.splitlines() if l.startswith("import ")})
# drop imports that reference the whole-screen-only helpers no longer needed anywhere
exclude_prefixes = ("import androidx.activity.compose.BackHandler", "import android.content.Intent")
clean_imports = [i for i in clean_imports if not i.startswith(exclude_prefixes)]

FILES = [
    "SettingsSectionEngine.kt",
    "SettingsSectionUsb.kt",
    "SettingsSectionRecording.kt",
    "SettingsSectionMetering.kt",
    "SettingsSectionAi.kt",
    "SettingsSectionStorage.kt",
    "SettingsDiagnostics.kt",
]

for fname in FILES:
    path = os.path.join(OUT_DIR, fname)
    lines = open(path, encoding="utf-8").read().splitlines()
    doc_idx = next(i for i, l in enumerate(lines) if l.strip().startswith("/**"))
    rest = lines[doc_idx:]
    out = ["package com.example.ui", "", "import androidx.compose.runtime.Composable"]
    out += [i for i in clean_imports if i != "import androidx.compose.runtime.Composable"]
    out += [""] + rest
    open(path, "w", encoding="utf-8", newline="\r\n").write("\n".join(out) + "\n")
    print("fixed imports:", fname)
