package com.qalsmartkeyboard.billing;

import java.util.Optional;

/**
 * The client build a purchase comes from: {@code X-Client-Platform} plus the distribution channel of the build (the
 * Android {@code play} flavour is {@code GOOGLE_PLAY}, {@code direct} is {@code DIRECT_DOWNLOAD}). Each pair maps to one
 * {@code sales_channel} of the price book and orders. The channel is asserted by the app build; it only selects which
 * market policy and routes apply, each of which must still be approved for that channel (ADR 0045).
 */
record Channel(String platform, String distribution) {

	/** {@code SalesChannel} of this build. */
	String salesChannel() {
		return switch (platform + "/" + distribution) {
			case "ANDROID/GOOGLE_PLAY" -> "ANDROID_GOOGLE_PLAY";
			case "ANDROID/DIRECT_DOWNLOAD" -> "ANDROID_DIRECT";
			case "IOS/APPLE_APP_STORE" -> "IOS_APP_STORE";
			case "IOS/DIRECT_DOWNLOAD" -> "IOS_DIRECT";
			case "WEB/WEB" -> "WEB";
			default -> throw new IllegalStateException("unsupported channel");
		};
	}

	static Optional<Channel> of(String platform, String distribution) {
		if (platform == null || distribution == null) {
			return Optional.empty();
		}
		Channel c = new Channel(platform, distribution);
		try {
			c.salesChannel();
			return Optional.of(c);
		}
		catch (IllegalStateException e) {
			return Optional.empty();
		}
	}

	static Optional<Channel> ofSalesChannel(String salesChannel) {
		if (salesChannel == null) {
			return Optional.empty();
		}
		return switch (salesChannel) {
			case "ANDROID_GOOGLE_PLAY" -> Optional.of(new Channel("ANDROID", "GOOGLE_PLAY"));
			case "ANDROID_DIRECT" -> Optional.of(new Channel("ANDROID", "DIRECT_DOWNLOAD"));
			case "IOS_APP_STORE" -> Optional.of(new Channel("IOS", "APPLE_APP_STORE"));
			case "IOS_DIRECT" -> Optional.of(new Channel("IOS", "DIRECT_DOWNLOAD"));
			case "WEB" -> Optional.of(new Channel("WEB", "WEB"));
			default -> Optional.empty();
		};
	}
}
