package org.jmixworkbench.generator

import org.jmixworkbench.model.ComponentModel
import org.jmixworkbench.model.ComponentType
import org.jmixworkbench.model.ViewModel
import org.jmixworkbench.model.ViewType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ViewControllerGeneratorTest {
    @Test
    fun `detail view uses the public primary detail contract`() {
        val source = ViewControllerGenerator.generate(
            ViewModel(
                viewName = "LoanApplicationDetailView",
                packageName = "com.acme.loan.view",
                viewType = ViewType.DETAIL_VIEW,
                entityClass = "com.acme.loan.entity.LoanApplication",
                layout = ComponentModel("root", ComponentType.VBOX),
            ),
        )

        assertContains(source, "import io.jmix.flowui.view.PrimaryDetailView;")
        assertContains(source, "@PrimaryDetailView(LoanApplication.class)")
        assertFalse(source.contains("PrimaryDetailDialog"))
    }
}
