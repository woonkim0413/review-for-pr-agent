package com.example.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelloControllerTest {
	@Test
	void hello_returnsGreeting() {
		HelloController controller = new HelloController();

		String result = controller.hello();

		assertEquals("Hello, Spring Boot!", result);
	}
}
