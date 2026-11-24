package com.qalsmartkeyboard;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundary test (B1.1). Runs in surefire, no database needed. */
class ModularityTests {

	@Test
	void verifiesModuleStructure() {
		ApplicationModules.of(QalSmartKeyboardApplication.class).verify();
	}

	@Test
	void expectedModulesAreDetected() {
		var modules = ApplicationModules.of(QalSmartKeyboardApplication.class);
		org.assertj.core.api.Assertions.assertThat(modules.stream().count()).isEqualTo(22);
	}
}
