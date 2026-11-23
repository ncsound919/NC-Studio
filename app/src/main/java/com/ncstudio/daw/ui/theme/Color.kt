package com.ncstudio.daw.ui.theme

import androidx.compose.ui.graphics.Color

// Pro Studio Dark Audio Palette — Overlay UI Protocol v2 surfaces
// (`shared/studio-ui/spec/studio-ui.v2.json`). Names are kept stable so
// screens do not change; values follow the protocol token ladder.
val StudioBackground = Color(0xFF08080A)
val StudioSurface = Color(0xFF0E0E11)
val StudioCard = Color(0xFF141417)
val StudioCardBorder = Color(0xFF26262C)

val NeonCyan = Color(0xFFFF8A00) // protocol accent — amber; name kept for call-site stability
val ElectricBlue = Color(0xFF35D0D8) // protocol mod cyan — modulation only
val ElectricLime = Color(0xFF55D18B) // protocol ok green
val StudioAmber = Color(0xFFFF8A00)
val AccentHot = Color(0xFFFFC98A)
val ClipRed = Color(0xFFFF5A4A)
val StudioPurple = Color(0xFFB98CFF)

// Overlay365 brand chrome under the Overlay UI Protocol: near-black warm
// shell, single amber accent. Meters and control states stay on the studio
// ramp above; headers and primary CTAs may use BrandGold / BrandAccentHot.
val BrandMidnight = Color(0xFF08080A)
val BrandGraphite = Color(0xFF0E0E11)
val BrandSlate = Color(0xFF141417)
val BrandCyanElec = Color(0xFFFFC98A) // protocol accent-hot
val BrandGold = Color(0xFFFF8A00)
val BrandGoldSoft = Color(0xFFFFC98A)
val BrandSoftWhite = Color(0xFFFBF9F5)

val TextPrimary = Color(0xFFFBF9F5)
val TextSecondary = Color(0xFFB5B0A6)
val TextMuted = Color(0xFF8B867D) // ≥4.5:1 against StudioBackground

val MeterGreen = Color(0xFF55D18B)
val MeterYellow = Color(0xFFFF8A00)
val MeterRed = Color(0xFFFF5A4A)

val FaderTrack = Color(0xFF0A0A0C)
val FaderThumb = Color(0xFFF1EEE7)

// Semantic panel tokens: screens must not declare raw hex values.
val PanelInset = Color(0xFF0A0A0C)
val PanelMuted = Color(0xFF101014)
val PanelDeep = Color(0xFF101014)
val TrackLaneBase = Color(0xFF08080A)
val TrackLaneSelected = Color(0xFF141417)
val TrackHeader = Color(0xFF0E0E11)
val GridMajor = Color(0xFF26262C)
val GridMinor = Color(0xFF1B1B20)
val RulerTick = Color(0xFF63605A)
val MasterStrip = Color(0xFF141417)
val SurfaceRaised = Color(0xFF1A1A1F)
val SurfaceSunken = Color(0xFF0A0A0C)

// Neutral roles that screens must not declare themselves: text/icons on the
// bright accent buttons, white overlay glyphs, and scrims.
val OnAccent = Color(0xFF1A0D00)
val OverlayWhite = Color(0xFFFBF9F5)
val Scrim = Color.Black
val Transparent = Color.Transparent
