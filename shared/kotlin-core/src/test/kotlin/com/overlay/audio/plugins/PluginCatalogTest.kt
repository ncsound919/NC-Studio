package com.overlay.audio.plugins

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginCatalogTest {

    @Test
    fun `every module has documented parameters with defaults inside the range`() {
        for (kind in PluginKind.entries) {
            val params = PluginCatalog.params(kind)
            assertTrue(params.isNotEmpty(), "$kind has no parameters")
            for (param in params) {
                assertTrue(param.max >= param.min, "${kind.name}.${param.key} has an inverted range")
                assertTrue(
                    param.default in param.min..param.max,
                    "${kind.name}.${param.key} default ${param.default} is outside ${param.min}..${param.max}",
                )
                if (param.isChoice) {
                    assertTrue(
                        param.choices.any { abs(it.first - param.default) < 1e-4f },
                        "${kind.name}.${param.key} default is not one of its selector positions",
                    )
                }
            }
        }
    }

    @Test
    fun `catalog manifest is valid JSON and covers every kind`() {
        val root = JSONObject(FxChainCodec.catalogJson())
        assertEquals(FxChainCodec.CATALOG_SCHEMA, root.getString("schema"))
        val kinds = root.getJSONArray("kinds")
        assertEquals(PluginKind.entries.size, kinds.length())
        for (i in 0 until kinds.length()) {
            val node = kinds.getJSONObject(i)
            assertTrue(node.getString("id").isNotBlank())
            assertTrue(node.getJSONArray("params").length() > 0)
        }
    }

    @Test
    fun `choice parameters snap to a documented selector position`() {
        val ratio = PluginCatalog.param(PluginKind.FET_1176, "ratio")!!
        assertEquals(8f, ratio.clamp(7.4f), "7.4:1 must snap to the 8:1 button")
        assertEquals(20f, ratio.clamp(100f), "out-of-range values snap to a real position, not the max")
    }

    @Test
    fun `default slot carries the full default parameter set`() {
        val slot = PluginCatalog.defaultSlot(PluginKind.PLATE_REVERB)
        val expected = PluginCatalog.defaultParams(PluginKind.PLATE_REVERB)
        assertEquals(expected.keys, slot.params.keys)
        assertEquals(expected.values.toList(), slot.params.values.toList())
    }
}

class FxChainCodecTest {

    @Test
    fun `round trip preserves order, bypass state and every parameter`() {
        val chain = PluginChain(
            listOf(
                PluginCatalog.defaultSlot(PluginKind.NEVE_1073).withParam("driveDb", 9.5f),
                PluginCatalog.defaultSlot(PluginKind.FET_1176).withParam("ratio", 8f).copy(enabled = false),
                PluginCatalog.defaultSlot(PluginKind.TAPE_MACHINE).withParam("speedIps", 30f),
                PluginCatalog.defaultSlot(PluginKind.PLATE_REVERB).withParam("mix", 0.42f),
            ),
        )
        val decoded = assertNotNull(FxChainCodec.decode(FxChainCodec.encode(chain, 132f)))
        assertEquals(chain.slots.size, decoded.slots.size)
        for (i in chain.slots.indices) {
            val a = chain.slots[i]
            val b = decoded.slots[i]
            assertEquals(a.id, b.id)
            assertEquals(a.kind, b.kind)
            assertEquals(a.enabled, b.enabled)
            for (spec in PluginCatalog.params(a.kind)) {
                assertEquals(a.value(spec.key), b.value(spec.key), 1e-6f, "${a.kind}.${spec.key}")
            }
        }
    }

    @Test
    fun `unknown schema is rejected instead of guessed`() {
        assertNull(FxChainCodec.decode("""{"schema":"nc.studiofx.v0","chain":[]}"""))
        assertNull(FxChainCodec.decode("""{"chain":[]}"""))
        assertNull(FxChainCodec.decode("not json"))
    }

    @Test
    fun `newer module kinds and parameters are dropped safely`() {
        val doc = """
            {"schema":"nc.studiofx.v1","chain":[
              {"id":"a","kind":"FUTURE_MODULE","enabled":true,"params":{}},
              {"id":"b","kind":"TRIM","enabled":true,"params":{"gainDb":6.0,"futureParam":2.0}},
              {"id":"c","kind":"TRIM","enabled":false}
            ]}
        """.trimIndent()
        val chain = assertNotNull(FxChainCodec.decode(doc))
        assertEquals(2, chain.slots.size)
        assertEquals(PluginKind.TRIM, chain.slots[0].kind)
        assertEquals(6f, chain.slots[0].value("gainDb"), 1e-6f)
        assertTrue(chain.slots[0].params.keys.none { it == "futureParam" })
        assertTrue(!chain.slots[1].enabled)
        // A missing parameter map still receives the documented defaults.
        assertEquals(0f, chain.slots[1].value("gainDb"), 1e-6f)
    }

    @Test
    fun `out of range values are clamped at the boundary`() {
        val doc = """{"schema":"nc.studiofx.v1","chain":[{"kind":"TRIM","params":{"gainDb":999.0}}]}"""
        val chain = assertNotNull(FxChainCodec.decode(doc))
        assertEquals(24f, chain.slots[0].value("gainDb"), 1e-6f)
    }
}

class FxOfflineRendererTest {

    private val sr = 48_000f

    @Test
    fun `empty chain is transparent`() {
        val input = FloatArray(512) { sin(it * 0.05).toFloat() * 0.25f }
        val out = FxOfflineRenderer.renderMono(input, sr, PluginChain())
        assertArrayEquals(input, out)
    }

    @Test
    fun `trim gain is applied without changing the buffer shape`() {
        val input = FloatArray(256) { 0.1f }
        val chain = PluginChain(listOf(PluginCatalog.defaultSlot(PluginKind.TRIM).withParam("gainDb", 6f)))
        val out = FxOfflineRenderer.renderMono(input, sr, chain)
        assertEquals(input.size, out.size)
        assertEquals(0.1f * Db.toLinear(6f), out[128], 1e-5f)
    }

    @Test
    fun `stereo interleaving is preserved`() {
        val input = FloatArray(256)
        for (i in 0 until 128) {
            input[i * 2] = 0.2f
            input[i * 2 + 1] = -0.2f
        }
        val chain = PluginChain(listOf(PluginCatalog.defaultSlot(PluginKind.TRIM).withParam("gainDb", -6f)))
        val out = FxOfflineRenderer.render(input, 2, sr, chain)
        assertEquals(256, out.size)
        assertEquals(0.2f * Db.toLinear(-6f), out[0], 1e-5f)
        assertEquals(-0.2f * Db.toLinear(-6f), out[1], 1e-5f)
    }

    @Test
    fun `a bypassed chain still returns finite audio`() {
        val input = FloatArray(512) { (it % 7 - 3) * 0.01f }
        val chain = PluginChain(PluginKind.entries.map { PluginCatalog.defaultSlot(it) })
        val out = FxOfflineRenderer.render(input, 1, sr, chain)
        assertTrue(out.all { it.isFinite() })
    }

    private fun assertArrayEquals(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-7f, "sample $i")
    }
}
