package app.pkuautobus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedState(val ticket: Ticket? = null, val pending: PendingAction? = null)

class TicketStore(context: Context) {
    private val preferences = context.getSharedPreferences("boarding", Context.MODE_PRIVATE)
    private val alias = "pkuautobus.boarding.v1"

    @Synchronized fun load(): SavedState {
        val stored = preferences.getString("encrypted", null) ?: return SavedState()
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return decode(JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)))
    }

    @Synchronized fun save(state: SavedState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(encode(state).toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "无法保存预约状态，请勿重复预约" }
    }

    @Synchronized fun clear() { check(preferences.edit().clear().commit()) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        fun encode(state: SavedState): JSONObject = JSONObject().apply {
            state.ticket?.let { t -> put("ticket", JSONObject().put("id", t.id).put("detailId", t.detailId).put("route", t.route.id)
                .put("at", t.at.toString()).put("address", t.address).put("canCancel", t.canCancel).apply {
                    t.code?.let { put("code", it.payload); put("passenger", it.passenger) }
                }) }
            state.pending?.let { p -> put("pending", JSONObject().put("kind", p.kind.name).put("route", p.key.route.id)
                .put("at", p.key.at.toString()).put("detailId", p.detailId)) }
        }
        fun decode(data: JSONObject): SavedState {
            val ticket = data.optJSONObject("ticket")?.let { t -> Ticket(t.getLong("id"), t.getLong("detailId"),
                requireNotNull(Route.fromId(t.getInt("route"))), Instant.parse(t.getString("at")), t.getString("address"), t.getBoolean("canCancel"),
                t.optString("code").takeIf { it.isNotEmpty() }?.let { BoardingCode(it, t.optString("passenger")) }) }
            val pending = data.optJSONObject("pending")?.let { p -> PendingAction(ActionKind.valueOf(p.getString("kind")),
                TripKey(requireNotNull(Route.fromId(p.getInt("route"))), Instant.parse(p.getString("at"))), p.getLong("detailId")) }
            return SavedState(ticket, pending)
        }
    }
}
