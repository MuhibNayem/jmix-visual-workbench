package org.jmixworkbench.host.idea253

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Idea253DescriptorTest {

    @Test
    fun `descriptor satisfies the idea253 host contract`() {
        val descriptor = readPackagedDescriptor()

        assertTrue(descriptor.contains("<id>org.jmixworkbench</id>"))
        assertTrue(descriptor.contains("<name>Jmix Visual Workbench</name>"))
        assertTrue(Regex("""<idea-version[^>]+since-build="253"[^>]+until-build="253\.\*"""").containsMatchIn(descriptor))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.platform</depends>"))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.java</depends>"))
        assertFalse(descriptor.contains("<depends>com.intellij.modules.jcef</depends>"))
    }

    private fun readPackagedDescriptor(): String {
        val resource = assertNotNull(javaClass.getResource("/META-INF/plugin.xml"))
        return resource.readText()
    }
}
