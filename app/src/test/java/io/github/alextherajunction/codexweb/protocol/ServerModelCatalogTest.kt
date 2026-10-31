package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModelCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `non-openai config replaces incompatible catalog models`() {
        val catalog = resolveServerModelCatalog(
            models = """
                {"data":[
                  {"id":"gpt-6-sol","model":"gpt-6-sol","displayName":"GPT-6-Sol","description":"Catalog model","isDefault":true,"hidden":false},
                  {"id":"gpt-6-astra","model":"gpt-6-astra","displayName":"GPT-6-Astra","description":"Catalog model","isDefault":false,"hidden":false}
                ]}
            """,
            config = """{"config":{"model":"deepseek-flash","model_provider":"deepseek"}}""",
        )

        assertEquals(listOf("deepseek-flash"), catalog.models.map { it.id })
        assertEquals("deepseek-flash", catalog.defaultModelId)
        assertEquals("Configured on the server (deepseek)", catalog.models.single().description)
    }

    @Test
    fun `configured catalog model is selected by its server alias`() {
        val catalog = resolveServerModelCatalog(
            models = """
                {"data":[
                  {"id":"display-id","model":"upstream-id","displayName":"Server display name","description":"","isDefault":false,"hidden":false},
                  {"id":"other","model":"other","displayName":"Other","description":"","isDefault":true,"hidden":false}
                ]}
            """,
            config = """{"config":{"model":"upstream-id","model_provider":"openai"}}""",
        )

        assertEquals(listOf("display-id", "other"), catalog.models.map { it.id })
        assertEquals("display-id", catalog.defaultModelId)
    }

    @Test
    fun `missing configured openai model comes from server config`() {
        val catalog = resolveServerModelCatalog(
            models = """
                {"data":[
                  {"id":"catalog-model","model":"catalog-model","displayName":"Catalog Model","description":"","isDefault":true,"hidden":false}
                ]}
            """,
            config = """{"config":{"model":"private-model","model_provider":"openai"}}""",
        )

        assertEquals(listOf("private-model", "catalog-model"), catalog.models.map { it.id })
        assertEquals("private-model", catalog.defaultModelId)
        assertFalse(catalog.models.any { it.id == "gpt-6-sol" })
    }

    @Test
    fun `hidden catalog entries are never exposed`() {
        val catalog = resolveServerModelCatalog(
            models = """
                {"data":[
                  {"id":"hidden-model","model":"hidden-model","displayName":"Hidden","description":"","isDefault":true,"hidden":true},
                  {"id":"visible-model","model":"visible-model","displayName":"Visible","description":"","isDefault":false,"hidden":false}
                ]}
            """,
            config = """{"config":{"model":null,"model_provider":"openai"}}""",
        )

        assertEquals(listOf("visible-model"), catalog.models.map { it.id })
        assertEquals("visible-model", catalog.defaultModelId)
    }

    @Test
    fun `catalog default is used when server config has no model`() {
        val catalog = resolveServerModelCatalog(
            models = """
                {"data":[
                  {"id":"first","model":"first","displayName":"First","description":"","isDefault":false,"hidden":false},
                  {"id":"default","model":"default","displayName":"Default","description":"","isDefault":true,"hidden":false}
                ]}
            """,
            config = """{"config":{"model":null,"model_provider":null}}""",
        )

        assertEquals("default", catalog.defaultModelId)
        assertTrue(catalog.models.all { it.description.isEmpty() })
    }

    private fun resolveServerModelCatalog(models: String, config: String): ServerModelCatalog =
        resolveServerModelCatalog(parse(models), parse(config))

    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)
}
