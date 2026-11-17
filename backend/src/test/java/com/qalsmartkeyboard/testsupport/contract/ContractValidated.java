package com.qalsmartkeyboard.testsupport.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Put on any {@code @WebMvcTest} / {@code @AutoConfigureMockMvc} controller test class so every request and
 * response it performs is validated against {@code contracts/openapi.yaml}:
 *
 * <pre>
 * &#64;WebMvcTest(MyController.class)
 * &#64;ContractValidated
 * class MyControllerTest { ... }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(ContractValidationConfiguration.class)
public @interface ContractValidated {
}
