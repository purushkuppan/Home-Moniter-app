package com.homehub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/context.db",
        "homehub.connectors.enabled=false"
})
class HomehubApplicationTests {

	@Test
	void contextLoads() {
	}

}
