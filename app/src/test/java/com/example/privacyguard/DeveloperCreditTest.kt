package com.example.privacyguard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer credit in Settings must keep the exact wording.
 * It reads the real strings.xml, so a typo or a changed word fails the test.
 */
class DeveloperCreditTest {

    private fun strings(): Map<String, String> {
        // Gradle runs unit tests with the app folder as working directory; Android Studio may use the project root.
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map { File(it) }
            .first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun creditNamesAreExact() {
        assertEquals("Made by Nurbhanej Anvarali and Momin Kumail", strings()["developer_credit"])
    }

    @Test fun creditRoleIsExact() {
        assertEquals("2nd Year CE Student at LDCE, Ahmedabad", strings()["developer_role"])
    }

    @Test fun aboutSectionHasATitleAndAVersionPlaceholder() {
        val s = strings()
        assertEquals("About & Developer", s["about_developer_title"])
        assertTrue(s["about_version"]!!.contains("%1\$s"))
    }
}
