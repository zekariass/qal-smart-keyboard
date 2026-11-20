/**
 * Billing-country detection from several request signals (ADR 0045). The backend is the final authority:
 * {@link com.qalsmartkeyboard.platform.geo.CountryResolver#resolve} combines the edge's {@code CF-IPCountry} (trusted only
 * when the request provably came through the configured edge), the app's client hints {@code X-Qal-Network-Country} and
 * {@code X-Qal-Sim-Country}, and the development override {@code DEV_MOCK_COUNTRY} into a
 * {@link com.qalsmartkeyboard.platform.geo.CountryResolution}. Nothing here logs or returns an IP address.
 */
@NamedInterface("geo")
package com.qalsmartkeyboard.platform.geo;

import org.springframework.modulith.NamedInterface;
