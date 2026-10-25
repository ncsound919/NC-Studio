package com.example.audio.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Thread-safe lock-free circular ring buffer for real-time audio streams.
 */
class AudioRingBuffer(val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private val writeHead = AtomicInteger(0)
    private val readHead = AtomicInteger(0)

    fun availableToRead(): Int {
        val w = writeHead.get()
        val r = readHead.get()
        return if (w >= r) w - r else capacity - (r - w)
    }

    fun availableToWrite(): Int {
        return capacity - availableToRead() - 1
    }

    fun write(data: FloatArray, offset: Int, count: Int): Int {
        val toWrite = Math.min(count, availableToWrite())
        if (toWrite <= 0) return 0

        var currentWrite = writeHead.get()
        for (i in 0 until toWrite) {
            buffer[currentWrite] = data[offset + i]
            currentWrite = (currentWrite + 1) % capacity
        }
        writeHead.set(currentWrite)
        return toWrite
    }

    fun read(destination: FloatArray, offset: Int, count: Int): Int {
        val toRead = Math.min(count, availableToRead())
        if (toRead <= 0) return 0

        var currentRead = readHead.get()
        for (i in 0 until toRead) {
            destination[offset + i] = buffer[currentRead]
            currentRead = (currentRead + 1) % capacity
        }
        readHead.set(currentRead)
        return toRead
    }

    fun clear() {
        writeHead.set(0)
        readHead.set(0)
    }
}

/**
 * High-performance WAV reader, writer, and synthetic stem generator.
 */
object WavAudioIO {

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalFrames: Long,
        val samples: FloatArray // interleaved L, R or mono
    )

    fun writeWavFile(
        file: File,
        interleavedSamples: FloatArray,
        channels: Int = 2,
        sampleRate: Int = 48000,
        bitsPerSample: Int = 16
    ) {
        val totalFrames = (interleavedSamples.size / channels)
        val bytesPerSample = bitsPerSample / 8
        val dataSize = totalFrames * channels * bytesPerSample
        val totalFileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalFileSize.toInt())
            header.put("WAVE".toByteArray())

            // "fmt " chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size (16 for PCM)
            header.putShort(1.toShort()) // AudioFormat (1 = PCM)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * bytesPerSample) // ByteRate
            header.putShort((channels * bytesPerSample).toShort()) // BlockAlign
            header.putShort(bitsPerSample.toShort())

            // "data" chunk
            header.put("data".toByteArray())
            header.putInt(dataSize.toInt())
            fos.write(header.array())

            // Write PCM audio payload in chunks
            val chunkFrames = 2048
            val chunkBytes = chunkFrames * channels * bytesPerSample
            val chunkBuffer = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

            var sampleIdx = 0
            while (sampleIdx < interleavedSamples.size) {
                chunkBuffer.clear()
                val batchCount = Math.min(interleavedSamples.size - sampleIdx, chunkFrames * channels)
                for (i in 0 until batchCount) {
                    val floatSample = interleavedSamples[sampleIdx++].coerceIn(-1.0f, 1.0f)
                    if (bitsPerSample == 16) {
                        val intVal = (floatSample * 32767.0f).toInt().coerceIn(-32768, 32767)
                        chunkBuffer.putShort(intVal.toShort())
                    } else if (bitsPerSample == 24) {
                        val intVal = (floatSample * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
                        chunkBuffer.put((intVal and 0xFF).toByte())
                        chunkBuffer.put(((intVal shr 8) and 0xFF).toByte())
                        chunkBuffer.put(((intVal shr 16) and 0xFF).toByte())
                    }
                }
                fos.write(chunkBuffer.array(), 0, chunkBuffer.position())
            }
        }
    }

    fun readWavFile(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            FileInputStream(file).use { fis ->
                val headerBytes = ByteArray(44)
                fis.read(headerBytes)
                val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                val riff = String(headerBytes, 0, 4)
                if (riff != "RIFF") return null

                val wave = String(headerBytes, 8, 4)
                if (wave != "WAVE") return null

                val channels = bb.getShort(22).toInt()
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt()
                val dataSize = bb.getInt(40)

                val bytesPerSample = bitsPerSample / 8
                val totalSamples = dataSize / bytesPerSample
                val samples = FloatArray(totalSamples)

                val rawBuffer = ByteArray(4096)
                var bytesRead: Int
                var sampleIndex = 0

                while (fis.read(rawBuffer).also { bytesRead = it } != -1 && sampleIndex < totalSamples) {
                    val rawBb = ByteBuffer.wrap(rawBuffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                    while (rawBb.remaining() >= bytesPerSample && sampleIndex < totalSamples) {
                        if (bitsPerSample == 16) {
                            val shortVal = rawBb.short
                            samples[sampleIndex++] = shortVal / 32768.0f
                        } else if (bitsPerSample == 24) {
                            val b0 = rawBb.get().toInt() and 0xFF
                            val b1 = rawBb.get().toInt() and 0xFF
                            val b2 = rawBb.get().toInt()
                            val intVal = (b2 shl 16) or (b1 shl 8) or b0
                            samples[sampleIndex++] = intVal / 8388608.0f
                        } else if (bitsPerSample == 32) {
                            val floatVal = rawBb.float
                            samples[sampleIndex++] = floatVal
                        }
                    }
                }

                WavInfo(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    totalFrames = (totalSamples / channels).toLong(),
                    samples = samples
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Synthesize rich procedural musical audio stems for default 8-track project.
     * Duration: 16 seconds loop at 120 BPM (32 beats / 8 bars).
     */
    fun generateProceduralStem(
        trackIndex: Int,
        sampleRate: Int = 48000,
        totalFrames: Int = 48000 * 16 // 16 seconds loop
    ): FloatArray {
        val stereoSamples = FloatArray(totalFrames * 2)
        val bpm = 120.0
        val beatFrames = (sampleRate * 60.0 / bpm).toInt()
        val sixteenthFrames = beatFrames / 4

        when (trackIndex) {
            0 -> {
                // Track 1: Kick Drum (4-on-the-floor + syncopated accents)
                for (f in 0 until totalFrames) {
                    val beatOffset = f % beatFrames
                    val beatIndex = f / beatFrames
                    val isKick = (beatOffset < sampleRate * 0.25) &&
                            (beatIndex % 1 == 0 || (beatIndex % 8 == 6 && f % sixteenthFrames < sampleRate * 0.15))
                    if (isKick) {
                        val t = (f % beatFrames).toDouble() / sampleRate
                        val freq = 130.0 * exp(-t * 28.0) + 42.0
                        val amp = exp(-t * 14.0)
                        val sample = (sin(2.0 * PI * freq * t) * amp * 0.9).toFloat()
                        stereoSamples[f * 2] = sample
                        stereoSamples[f * 2 + 1] = sample
                    }
                }
            }
            1 -> {
                // Track 2: Snare & Claps (Beats 2 and 4 with reverb tail)
                for (f in 0 until totalFrames) {
                    val beatIndex = (f / beatFrames) % 4
                    val beatOffset = f % beatFrames
                    if ((beatIndex == 1 || beatIndex == 3) && beatOffset < sampleRate * 0.3) {
                        val t = beatOffset.toDouble() / sampleRate
                        val noise = (Math.random() * 2.0 - 1.0) * exp(-t * 18.0)
                        val tone = sin(2.0 * PI * 185.0 * t) * exp(-t * 25.0)
                        val sample = ((noise * 0.75 + tone * 0.25) * 0.85).toFloat()
                        stereoSamples[f * 2] = sample * 0.95f
                        stereoSamples[f * 2 + 1] = sample * 1.05f
                    }
                }
            }
            2 -> {
                // Track 3: Hi-Hats & Shakers (16th-note groove with dynamic velocities)
                for (f in 0 until totalFrames) {
                    val sixteenth = (f / sixteenthFrames) % 16
                    val sixteenthOffset = f % sixteenthFrames
                    if (sixteenthOffset < sampleRate * 0.05) {
                        val t = sixteenthOffset.toDouble() / sampleRate
                        val velocity = if (sixteenth % 4 == 2) 0.65 else if (sixteenth % 2 == 1) 0.4 else 0.85
                        val noise = (Math.random() * 2.0 - 1.0) * exp(-t * 80.0) * velocity
                        val sample = (noise * 0.45).toFloat()
                        stereoSamples[f * 2] = sample * 0.8f
                        stereoSamples[f * 2 + 1] = sample * 1.2f // wide stereo
                    }
                }
            }
            3 -> {
                // Track 4: 808 Sub Bass (Punchy fundamental walking root notes)
                val bassNotes = doubleArrayOf(43.65, 43.65, 51.91, 38.89, 43.65, 43.65, 58.27, 48.99) // F1, F1, Ab1, Eb1...
                for (f in 0 until totalFrames) {
                    val bar = (f / (beatFrames * 4)) % bassNotes.size
                    val t = f.toDouble() / sampleRate
                    val noteFreq = bassNotes[bar]
                    val beatOffset = f % beatFrames
                    val tNote = beatOffset.toDouble() / sampleRate
                    val env = exp(-tNote * 2.2).coerceIn(0.0, 1.0)
                    val s1 = sin(2.0 * PI * noteFreq * t)
                    val s2 = sin(2.0 * PI * noteFreq * 2.0 * t) * 0.25
                    val sample = ((s1 + s2) * env * 0.85).toFloat()
                    stereoSamples[f * 2] = sample
                    stereoSamples[f * 2 + 1] = sample
                }
            }
            4 -> {
                // Track 5: Rhythm Guitar / Chord Chomp (Offbeat reggae/funk rhythm)
                val chords = arrayOf(
                    doubleArrayOf(174.6, 220.0, 261.6), // F Maj
                    doubleArrayOf(207.6, 261.6, 311.1), // Ab Maj
                    doubleArrayOf(155.5, 196.0, 233.0), // Eb Maj
                    doubleArrayOf(196.0, 246.9, 293.6)  // G Min
                )
                for (f in 0 until totalFrames) {
                    val bar = (f / (beatFrames * 4)) % chords.size
                    val beatOffset = f % beatFrames
                    val isOffbeat = beatOffset in (beatFrames / 2)..(beatFrames * 3 / 4)
                    if (isOffbeat) {
                        val tSub = (beatOffset - beatFrames / 2).toDouble() / sampleRate
                        val tGlobal = f.toDouble() / sampleRate
                        val env = exp(-tSub * 15.0)
                        val chord = chords[bar]
                        var sum = 0.0
                        for (fq in chord) {
                            sum += sin(2.0 * PI * fq * tGlobal) + 0.3 * sin(2.0 * PI * fq * 2.0 * tGlobal)
                        }
                        val sample = (sum * 0.2 * env).toFloat()
                        stereoSamples[f * 2] = sample * 1.1f
                        stereoSamples[f * 2 + 1] = sample * 0.9f
                    }
                }
            }
            5 -> {
                // Track 6: Synth Pad / Atmosphere (Smooth lush stereo pad)
                for (f in 0 until totalFrames) {
                    val t = f.toDouble() / sampleRate
                    val pL = sin(2.0 * PI * 349.2 * t) + 0.5 * sin(2.0 * PI * 440.0 * t) + 0.3 * sin(2.0 * PI * 523.2 * t)
                    val pR = sin(2.0 * PI * 349.6 * t) + 0.5 * sin(2.0 * PI * 440.5 * t) + 0.3 * sin(2.0 * PI * 524.0 * t)
                    val lfo = 0.75 + 0.25 * sin(2.0 * PI * 0.25 * t)
                    stereoSamples[f * 2] = (pL * 0.22 * lfo).toFloat()
                    stereoSamples[f * 2 + 1] = (pR * 0.22 * lfo).toFloat()
                }
            }
            6 -> {
                // Track 7: Lead Vocal Line (Melodic formant-rich synth vocal lead)
                val vocalMelody = doubleArrayOf(349.23, 392.0, 440.0, 523.25, 440.0, 392.0, 349.23, 261.63)
                for (f in 0 until totalFrames) {
                    val noteIdx = (f / (beatFrames * 2)) % vocalMelody.size
                    val t = f.toDouble() / sampleRate
                    val freq = vocalMelody[noteIdx]
                    val vibrato = 1.0 + 0.015 * sin(2.0 * PI * 5.5 * t)
                    val lead = sin(2.0 * PI * (freq * vibrato) * t) +
                            0.4 * sin(2.0 * PI * (freq * vibrato * 2.0) * t) +
                            0.15 * sin(2.0 * PI * (freq * vibrato * 3.0) * t)
                    val noteEnv = 0.5 + 0.5 * sin(PI * ((f % (beatFrames * 2)).toDouble() / (beatFrames * 2)))
                    val sample = (lead * 0.35 * noteEnv).toFloat()
                    stereoSamples[f * 2] = sample
                    stereoSamples[f * 2 + 1] = sample
                }
            }
            7 -> {
                // Track 8: Vocal Chop & Backing FX (Stereo wide ambient ad-libs)
                for (f in 0 until totalFrames) {
                    val bar = (f / (beatFrames * 4)) % 4
                    val beatInBar = (f / beatFrames) % 4
                    val t = f.toDouble() / sampleRate
                    if (bar % 2 == 1 && beatInBar >= 2) {
                        val subT = (f % (beatFrames * 2)).toDouble() / sampleRate
                        val env = exp(-subT * 4.0)
                        val pitch = 698.46 * (1.0 + 0.05 * exp(-subT * 10.0))
                        val sample = (sin(2.0 * PI * pitch * t) * env * 0.28).toFloat()
                        stereoSamples[f * 2] = (sample * sin(2.0 * PI * 0.5 * t)).toFloat()
                        stereoSamples[f * 2 + 1] = (sample * cos(2.0 * PI * 0.5 * t)).toFloat()
                    }
                }
            }
            8 -> {
                // Track 9: Toms & Percs (Acoustic tom fills on beat 4 of every 2 bars)
                for (f in 0 until totalFrames) {
                    val bar = (f / (beatFrames * 4)) % 8
                    val beatInBar = (f / beatFrames) % 4
                    val beatOffset = f % beatFrames
                    if (bar % 2 == 1 && beatInBar == 3 && beatOffset < sampleRate * 0.2) {
                        val t = beatOffset.toDouble() / sampleRate
                        val freq = 110.0 * exp(-t * 12.0)
                        val sample = (sin(2.0 * PI * freq * t) * exp(-t * 10.0) * 0.65).toFloat()
                        stereoSamples[f * 2] = sample * 0.9f
                        stereoSamples[f * 2 + 1] = sample * 1.1f
                    }
                }
            }
            9 -> {
                // Track 10: Synth Bass (Plucked saw bass with envelope filter)
                val notes = doubleArrayOf(87.31, 87.31, 103.83, 77.78) // F2, Ab2, Eb2
                for (f in 0 until totalFrames) {
                    val noteIdx = (f / (beatFrames * 2)) % notes.size
                    val beatOffset = f % (beatFrames / 2)
                    val tSub = beatOffset.toDouble() / sampleRate
                    val tGlobal = f.toDouble() / sampleRate
                    val env = exp(-tSub * 8.0)
                    val fq = notes[noteIdx]
                    val saw = (2.0 * ((fq * tGlobal) - Math.floor(fq * tGlobal + 0.5)))
                    val sample = (saw * env * 0.30).toFloat()
                    stereoSamples[f * 2] = sample
                    stereoSamples[f * 2 + 1] = sample
                }
            }
            10 -> {
                // Track 11: Rhythm Guitar R (Stereo wide chord counterpart)
                val chords = arrayOf(
                    doubleArrayOf(174.6, 220.0, 261.6), // F Maj
                    doubleArrayOf(207.6, 261.6, 311.1), // Ab Maj
                    doubleArrayOf(155.5, 196.0, 233.0), // Eb Maj
                    doubleArrayOf(196.0, 246.9, 293.6)  // G Min
                )
                for (f in 0 until totalFrames) {
                    val bar = (f / (beatFrames * 4)) % chords.size
                    val beatOffset = f % beatFrames
                    val isOffbeat = beatOffset in (beatFrames / 2)..(beatFrames * 3 / 4)
                    if (isOffbeat) {
                        val tSub = (beatOffset - beatFrames / 2).toDouble() / sampleRate
                        val tGlobal = f.toDouble() / sampleRate
                        val env = exp(-tSub * 14.0)
                        val chord = chords[bar]
                        var sum = 0.0
                        for (fq in chord) {
                            sum += sin(2.0 * PI * fq * 1.002 * tGlobal) + 0.3 * sin(2.0 * PI * fq * 2.004 * tGlobal)
                        }
                        val sample = (sum * 0.18 * env).toFloat()
                        stereoSamples[f * 2] = sample * 0.85f
                        stereoSamples[f * 2 + 1] = sample * 1.15f // Wide Right
                    }
                }
            }
            11 -> {
                // Track 12: Lead Guitar (Melodic bends and sustain)
                val leadNotes = doubleArrayOf(349.23, 440.0, 523.25, 659.25, 523.25, 440.0)
                for (f in 0 until totalFrames) {
                    val idx = (f / (beatFrames * 2)) % leadNotes.size
                    val t = f.toDouble() / sampleRate
                    val freq = leadNotes[idx]
                    val vibrato = 1.0 + 0.01 * sin(2.0 * PI * 6.0 * t)
                    val tone = sin(2.0 * PI * freq * vibrato * t) + 0.4 * sin(2.0 * PI * freq * 2f * vibrato * t)
                    val sample = (tone * 0.22).toFloat()
                    stereoSamples[f * 2] = sample * 1.05f
                    stereoSamples[f * 2 + 1] = sample * 0.95f
                }
            }
            12 -> {
                // Track 13: Acoustic Piano (Rich grand piano chords on beat 1)
                for (f in 0 until totalFrames) {
                    val beatOffset = f % (beatFrames * 4)
                    if (beatOffset < sampleRate * 1.2) {
                        val t = beatOffset.toDouble() / sampleRate
                        val env = exp(-t * 2.5)
                        val chord = doubleArrayOf(174.61, 220.0, 261.63, 349.23) // F Maj7
                        var sum = 0.0
                        for (fq in chord) {
                            sum += sin(2.0 * PI * fq * t) * 0.25
                        }
                        val sample = (sum * env * 0.7).toFloat()
                        stereoSamples[f * 2] = sample
                        stereoSamples[f * 2 + 1] = sample
                    }
                }
            }
            13 -> {
                // Track 14: Rhodes E-Piano (Bell-like chorus chords)
                for (f in 0 until totalFrames) {
                    val t = f.toDouble() / sampleRate
                    val env = 0.5 + 0.5 * sin(2.0 * PI * 0.5 * t)
                    val bell = sin(2.0 * PI * 440.0 * t) + 0.5 * sin(2.0 * PI * 880.0 * t)
                    val sample = (bell * 0.15 * env).toFloat()
                    stereoSamples[f * 2] = sample * 1.1f
                    stereoSamples[f * 2 + 1] = sample * 0.9f
                }
            }
            14 -> {
                // Track 15: Synth Lead (Arpeggiated 16th notes)
                val arpFreqs = doubleArrayOf(349.23, 440.0, 523.25, 698.46)
                for (f in 0 until totalFrames) {
                    val sixteenth = (f / sixteenthFrames) % arpFreqs.size
                    val tSub = (f % sixteenthFrames).toDouble() / sampleRate
                    val tGlobal = f.toDouble() / sampleRate
                    val env = exp(-tSub * 12.0)
                    val fq = arpFreqs[sixteenth]
                    val sample = (sin(2.0 * PI * fq * tGlobal) * env * 0.22).toFloat()
                    stereoSamples[f * 2] = sample * 0.9f
                    stereoSamples[f * 2 + 1] = sample * 1.1f
                }
            }
            15 -> {
                // Track 16: FX Riser & Drops (Filtered noise sweep and ambient pad)
                for (f in 0 until totalFrames) {
                    val t = f.toDouble() / sampleRate
                    val lfo = 0.5 + 0.5 * sin(2.0 * PI * 0.5 * t)
                    val noise = (Math.random() * 2.0 - 1.0) * 0.15 * lfo
                    val sample = noise.toFloat()
                    stereoSamples[f * 2] = sample
                    stereoSamples[f * 2 + 1] = sample
                }
            }
        }
        return stereoSamples
    }

    /**
     * Generate DSP unit test signals: Sine, Dirac Impulse, and White Noise.
     */
    fun generateTestSignal(type: String, durationFrames: Int = 48000, sampleRate: Int = 48000): FloatArray {
        val stereo = FloatArray(durationFrames * 2)
        when (type.lowercase()) {
            "sine" -> {
                val freq = 1000.0 // 1 kHz test tone at -6 dBFS
                val amp = 0.5
                for (i in 0 until durationFrames) {
                    val t = i.toDouble() / sampleRate
                    val sample = (sin(2.0 * PI * freq * t) * amp).toFloat()
                    stereo[i * 2] = sample
                    stereo[i * 2 + 1] = sample
                }
            }
            "impulse" -> {
                // Dirac impulse delta[n] at sample 100
                if (100 < durationFrames) {
                    stereo[100 * 2] = 1.0f
                    stereo[100 * 2 + 1] = 1.0f
                }
            }
            "noise" -> {
                // White Gaussian Noise
                for (i in 0 until durationFrames * 2) {
                    stereo[i] = ((Math.random() * 2.0 - 1.0) * 0.25).toFloat()
                }
            }
        }
        return stereo
    }
}
