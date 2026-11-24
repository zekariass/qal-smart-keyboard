package com.qalsmartkeyboard.app.billing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.qalsmartkeyboard.app.MainActivity

/**
 * The landing point of `qalkeyboard://billing/return?…` (ADR 0047 amendment M4.11): the server's return redirect after
 * the hosted checkout. It looks only at scheme, host and path, ignores the query, brings the existing [MainActivity]
 * (and its plans screen) to the front and finishes. The resumed plans screen then asks the server to verify; nothing
 * in the URI marks anything paid.
 */
class BillingReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isBillingReturn(intent?.data)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    internal companion object {
        fun isBillingReturn(uri: Uri?): Boolean =
            uri != null && uri.scheme == CheckoutUrlPolicy.APP_RETURN_SCHEME && uri.host == "billing" && uri.path == "/return"
    }
}
