package com.qalsmartkeyboard.administration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code qal.admin.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminAccess.Properties.class)
class AdministrationConfiguration {
}
