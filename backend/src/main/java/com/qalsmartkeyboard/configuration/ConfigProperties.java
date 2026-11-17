package com.qalsmartkeyboard.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param cacheTtl how long the loaded flags/configuration snapshot is reused before the next request reloads it
 *                 (changes in the tables become visible within this time)
 */
@ConfigurationProperties("qal.config")
record ConfigProperties(@DefaultValue("30s") Duration cacheTtl) {
}
