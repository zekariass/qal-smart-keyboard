package com.qalsmartkeyboard.personalization;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code qal.creative-backup.*} (ADR 0050). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CreativeBackupProperties.class)
class CreativeBackupConfiguration {
}
