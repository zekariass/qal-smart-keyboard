# android/billing-play

Global store billing for the `play` flavour (ADR 0047): the RevenueCat SDK (`com.revenuecat.purchases:purchases`)
behind `RevenueCatStore`. Only `:app`'s `play` source set depends on this module, so the `direct` APK carries no store
SDK. Configured with a **public** RevenueCat Android key (`qal.revenuecatAndroidKey`, see `android/app/build.gradle.kts`);
empty = store billing hidden. Never put a secret (`sk_...`) key in the app: the build refuses one.

The app never unlocks from a store answer: purchases are reported to `POST /billing/store-purchases` and the plan comes
from the server's verified entitlement.
