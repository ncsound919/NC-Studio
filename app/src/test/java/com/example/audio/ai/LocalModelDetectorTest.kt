package com.example.audio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalModelDetectorTest {

    @Test
    fun testOllamaTagsJsonParsing() {
        val sampleOllamaJson = """
            {
              "models": [
                {
                  "name": "llama3.2:1b",
                  "modified_at": "2024-10-20T12:00:00Z",
                  "size": 1300000000,
                  "details": {
                    "family": "llama",
                    "parameter_size": "1.3B"
                  }
                },
                {
                  "name": "qwen2.5:0.5b",
                  "modified_at": "2024-10-21T08:30:00Z",
                  "size": 520000000,
                  "details": {
                    "family": "qwen2",
                    "parameter_size": "0.5B"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsedModels = LocalModelDetector.parseOllamaTagsResponse(sampleOllamaJson, "http://10.0.2.2:11434")

        assertEquals(2, parsedModels.size)
        val m1 = parsedModels[0]
        assertEquals("llama3.2:1b", m1.name)
        assertEquals(LocalModelProvider.OLLAMA, m1.provider)
        assertEquals("http://10.0.2.2:11434", m1.baseUrl)
        assertEquals("1.3B", m1.parameterSize)
        assertEquals("llama", m1.family)

        val m2 = parsedModels[1]
        assertEquals("qwen2.5:0.5b", m2.name)
        assertEquals("0.5B", m2.parameterSize)
    }

    @Test
    fun testLlamaCppModelsJsonParsing() {
        val sampleLlamaCppJson = """
            {
              "object": "list",
              "data": [
                {
                  "id": "phi3-mini-4k-instruct-q4",
                  "object": "model",
                  "created": 1700000000,
                  "owned_by": "llamacpp"
                }
              ]
            }
        """.trimIndent()

        val parsedModels = LocalModelDetector.parseLlamaCppModelsResponse(sampleLlamaCppJson, "http://10.0.2.2:8080")

        assertEquals(1, parsedModels.size)
        val m1 = parsedModels[0]
        assertEquals("phi3-mini-4k-instruct-q4", m1.name)
        assertEquals(LocalModelProvider.LLAMA_CPP, m1.provider)
        assertEquals("http://10.0.2.2:8080", m1.baseUrl)
    }

    @Test
    fun testProbeOfflineEndpointHandledGracefully() {
        val offlineEndpoint = LocalModelEndpoint(
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://127.0.0.1:59999", // Unreachable port
            displayName = "Offline Test Endpoint"
        )

        val result = LocalModelDetector.probeEndpoint(offlineEndpoint)

        assertFalse(result.isOnline)
        assertNotNull(result.errorMessage)
        assertTrue(result.models.isEmpty())
    }

    @Test
    fun testOfflineFallbackGeneration() {
        val model = DiscoveredModelInfo(
            name = "llama3.2:1b",
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://127.0.0.1:59999"
        )

        val response = LocalModelDetector.generateWithLocalModel(
            model = model,
            prompt = "How to clean vocal mud and adjust high-shelf EQ?"
        )

        assertNotNull(response)
        assertTrue(response.contains("llama3.2:1b"))
        assertTrue(response.contains("vocal clarity"))
    }
}
