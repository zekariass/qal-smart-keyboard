# android/billing-direct

Direct-download (`direct` flavour) billing module. Plan step M4.8. The direct APK buys through the local gateways
(ArifPay default, Chapa) with the hosted checkout in `:app` (`billing/`, ADR 0047), which needs no SDK; this module
stays empty and carries no store SDK.
