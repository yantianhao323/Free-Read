package me.ash.reader.domain.model.bypass

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleManagerTest {
    @Test
    fun testParseSitesJson() {
        val json = Json { ignoreUnknownKeys = true }
        val sitesJsonFile = File("src/main/assets/sites.json")
        assertTrue("sites.json should exist", sitesJsonFile.exists())
        
        val content = sitesJsonFile.readText()
        try {
            val rules = json.decodeFromString<Map<String, SiteRule>>(content)
            println("Successfully parsed ${rules.size} rules")
            assertTrue("Rules should not be empty", rules.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
