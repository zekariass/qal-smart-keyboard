/**
 * Provider adapter SPI (B5.1, ADR 0014): the typed contract between the AI gateway pipeline (B5.3) and a translation,
 * LLM or language-identification vendor, plus the descriptor/data-policy model the registry filters on. Vendor-neutral:
 * nothing here names a vendor, and user text only travels inside {@link com.qalsmartkeyboard.aigateway.spi.UserText}.
 */
@NamedInterface("spi")
package com.qalsmartkeyboard.aigateway.spi;

import org.springframework.modulith.NamedInterface;
