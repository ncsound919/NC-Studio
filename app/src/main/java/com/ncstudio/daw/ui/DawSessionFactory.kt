package com.ncstudio.daw.ui

import com.ncstudio.daw.audio.model.ProjectSession
import com.ncstudio.daw.audio.model.StemCategory
import com.ncstudio.daw.audio.model.TrackData

internal fun createInitialSession(): ProjectSession {
        val trackColors = longArrayOf(
            0xFFFF5722, // 1. Kick: Deep Orange
            0xFFFF9800, // 2. Snare: Amber
            0xFFFFEB3B, // 3. Hi-Hats: Yellow
            0xFF8BC34A, // 4. Toms: Light Green
            0xFF4CAF50, // 5. Sub Bass: Green
            0xFF009688, // 6. Synth Bass: Teal
            0xFF00BCD4, // 7. Rhythm Guitars L: Cyan
            0xFF03A9F4, // 8. Rhythm Guitars R: Light Blue
            0xFF2196F3, // 9. Lead Guitar: Blue
            0xFF3F51B5, // 10. Acoustic Piano: Indigo
            0xFF673AB7, // 11. Rhodes E-Piano: Deep Purple
            0xFF9C27B0, // 12. Synth Pad: Purple
            0xFFE91E63, // 13. Synth Lead: Pink
            0xFFF44336, // 14. Lead Vocal: Red / Rose
            0xFFFF4081, // 15. Vocal Harmonies: Magenta
            0xFFCDDC39  // 16. FX Riser & Drops: Lime
        )
        val trackNames = arrayOf(
            "Kick Drum",
            "Snare & Clap",
            "Hi-Hats Groove",
            "Toms & Percs",
            "808 Sub Bass",
            "Synth Bass",
            "Rhythm Guitar L",
            "Rhythm Guitar R",
            "Lead Guitar",
            "Acoustic Piano",
            "Rhodes E-Piano",
            "Synth Pad",
            "Synth Lead",
            "Lead Vocal",
            "Vocal Harmonies",
            "FX Riser & Drops"
        )
        val defaultCategories = arrayOf(
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.DRUMS,
            StemCategory.BASS,
            StemCategory.BASS,
            StemCategory.GUITAR,
            StemCategory.GUITAR,
            StemCategory.GUITAR,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.KEYS,
            StemCategory.VOCAL,
            StemCategory.VOCAL,
            StemCategory.OTHER
        )

        val tracks = (0 until 16).map { i ->
            TrackData(
                index = i,
                name = trackNames[i],
                colorHex = trackColors[i],
                gainDb = when (i) {
                    0, 4 -> -1.5f // Kick and Sub
                    6 -> -3.5f    // Guitar L
                    7 -> -3.5f    // Guitar R
                    else -> -3.0f
                },
                pan = when (i) {
                    2 -> 0.20f   // Hi-hats
                    6 -> -0.40f  // Rhythm Guitar L
                    7 -> 0.40f   // Rhythm Guitar R
                    10 -> -0.25f // Rhodes
                    14 -> 0.30f  // Harmonies
                    else -> 0.0f
                },
                reverbSendLevel = when (i) {
                    1 -> 0.35f  // Snare
                    9 -> 0.25f  // Acoustic Piano
                    11 -> 0.45f // Synth Pad
                    13 -> 0.30f // Lead Vocal
                    14 -> 0.50f // Harmonies
                    15 -> 0.60f // FX Riser
                    else -> 0.05f
                },
                stemCategory = defaultCategories[i],
                // Tracks start empty. Audio arrives from import or recording;
                // no stock music is baked into a new session.
                clips = emptyList()
            )
        }

        return ProjectSession(tracks = tracks)
    }
