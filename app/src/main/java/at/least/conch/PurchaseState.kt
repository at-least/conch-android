package at.least.conch

/**
 * One-time "remove ads" purchase state. The unlock flag is persisted in
 * [SecretsStore] (Android Keystore encrypted) so it cannot be flipped by
 * editing plain files. Play-build billing wiring calls [setAdFree] after a
 * verified purchase.
 */
object PurchaseState {

    private const val KEY = "iap:noads"

    @Volatile
    private var cached: Boolean? = null

    fun isAdFree(): Boolean {
        cached?.let { return it }
        val v = SecretsStore.get(KEY) == "1"
        cached = v
        return v
    }

    fun setAdFree() {
        SecretsStore.put(KEY, "1")
        cached = true
    }
}
