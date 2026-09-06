package fi.crewradio

import android.content.Context
import androidx.preference.PreferenceManager
import fi.crewradio.audio.AudioConfig

/**
 * What a setting may be. Pure Kotlin so the rules are unit-tested; [SettingsActivity]
 * uses them to refuse bad input and [Prefs] to fall back to a default if a bad value
 * ever reaches storage anyway.
 */
object SettingsRules {
    const val DEFAULT_GROUP = "239.255.42.1"
    const val DEFAULT_PORT = 47474
    /** Characters a generated channel key is made of: no 0/O, 1/l/I, so it survives being read out loud. */
    const val KEY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    const val DEFAULT_HOPS = AudioConfig.DEFAULT_TTL

    /** Empty means "use the device name"; otherwise it has to fit a [Hello] and stay on one line. */
    fun validName(s: String): Boolean =
        s.trim().toByteArray(Charsets.UTF_8).size <= Hello.MAX_NAME_BYTES && s.none { it == '\r' || it == '\n' }

    /** An IPv4 multicast address: dotted quad, first octet 224–239. */
    fun validGroup(s: String): Boolean {
        val parts = s.trim().split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } && octets[0] in 224..239
    }

    /** An unprivileged port. */
    fun validPort(s: String): Boolean = s.trim().toIntOrNull()?.let { it in 1024..65535 } == true

    /**
     * The channel key: what WifiAwareNetworkSpecifier.setPskPassphrase accepts, 8–63 printable
     * ASCII characters, since the same key is the Aware passphrase and the packet key.
     */
    fun validPassphrase(s: String): Boolean =
        s.length in 8..63 && s.all { it.code in 0x20..0x7E }

    /** A fresh random channel key, `xxxx-xxxx-xxxx` from [KEY_ALPHABET]: 14 characters, ~59 bits, readable aloud. */
    fun generateChannelKey(random: java.util.Random = java.security.SecureRandom()): String =
        (1..3).joinToString("-") { (1..4).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("") }

    /** The header name: short enough to stay on one line at 30 sp. Empty means the app name. */
    fun validCrewName(s: String): Boolean = s.trim().length <= 24 && s.none { it == '\r' || it == '\n' }

    /** Relays a packet may cross; 1 means no relaying at all. */
    fun validHops(s: String): Boolean = s.trim().toIntOrNull()?.let { it in 1..16 } == true
}

/**
 * Typed access to the app's SharedPreferences: the settings screen's values, with
 * defaults, plus the main screen's last choices so the crew can open the app and
 * press Connect. The settings screen writes the same file through the preference
 * framework, which is why the keys live here.
 */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val appName = context.getString(R.string.app_name)

    /** What the header shows: the crew or boat name, else the app name. */
    val crewName: String get() = sp.getString(KEY_CREW_NAME, null)?.trim()?.takeIf { it.isNotEmpty() && SettingsRules.validCrewName(it) } ?: appName

    /** The name to announce, or null to use the device name. */
    val name: String? get() = sp.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() && SettingsRules.validName(it) }
    val group: String get() = sp.getString(KEY_GROUP, null)?.trim()?.takeIf { SettingsRules.validGroup(it) } ?: SettingsRules.DEFAULT_GROUP
    val port: Int get() = sp.getString(KEY_PORT, null)?.trim()?.takeIf { SettingsRules.validPort(it) }?.toInt() ?: SettingsRules.DEFAULT_PORT
    /**
     * The crew's channel key: the Wi-Fi Aware passphrase and the packet encryption key. Never a
     * shared default: a phone without one generates a random key on first use (secure by default)
     * and the crew copies it to the other phones. An old install's Aware passphrase, if it was
     * changed from the former default, carries over so an existing crew keeps working.
     */
    val channelKey: String
        get() {
            sp.getString(KEY_CHANNEL_KEY, null)?.takeIf { SettingsRules.validPassphrase(it) }?.let { return it }
            val legacy = sp.getString(KEY_LEGACY_PASSPHRASE, null)?.takeIf { SettingsRules.validPassphrase(it) && it != "crew-radio" }
            val key = legacy ?: SettingsRules.generateChannelKey()
            sp.edit().putString(KEY_CHANNEL_KEY, key).apply()
            return key
        }
    val hops: Int get() = sp.getString(KEY_HOPS, null)?.trim()?.takeIf { SettingsRules.validHops(it) }?.toInt() ?: SettingsRules.DEFAULT_HOPS

    val fullDuplex: Boolean get() = sp.getBoolean(KEY_FULL_DUPLEX, false)
    /** Which physical buttons key the mic while on channel: off, headset, volume or both. */
    val hwButton: String get() = sp.getString(KEY_HW_BUTTON, HW_BOTH) ?: HW_BOTH
    val keepScreenOn: Boolean get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
    /** Where the voice goes: auto (headset, else loudspeaker), the loudspeaker, or the earpiece. */
    val audioRoute: String get() = sp.getString(KEY_AUDIO_ROUTE, ROUTE_AUTO)?.takeIf { it == ROUTE_SPEAKER || it == ROUTE_EARPIECE } ?: ROUTE_AUTO
    /** Register the session as a call while a Bluetooth headset is in use; for headsets whose button hangs up. */
    val headsetAsCall: Boolean get() = sp.getBoolean(KEY_HEADSET_CALL, false)
    /** With a Bluetooth headset, speech keys the mic (VOX). */
    val headsetVox: Boolean get() = sp.getBoolean(KEY_HEADSET_VOX, false)
    /** A tone in the ear when a talk key keys or un-keys the mic. */
    val cueTones: Boolean get() = sp.getBoolean(KEY_CUE_TONES, false)
    /** Read the proximity sensor: earpiece and voice keying at the ear, screen dark meanwhile. Off: the phone acts as one without the sensor. */
    val proximitySensor: Boolean get() = sp.getBoolean(KEY_PROXIMITY, true)
    val relay: Boolean get() = sp.getBoolean(KEY_RELAY, true)
    val opus: Boolean get() = sp.getBoolean(KEY_OPUS, true)

    fun bool(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    fun string(key: String): String? = sp.getString(key, null)
    fun put(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    companion object {
        // Settings screen (see res/xml/preferences.xml — the keys must match).
        const val KEY_CREW_NAME = "crew_name"
        const val KEY_NAME = "display_name"
        const val KEY_GROUP = "multicast_group"
        const val KEY_PORT = "lan_port"
        const val KEY_CHANNEL_KEY = "channel_key"
        const val KEY_LEGACY_PASSPHRASE = "aware_passphrase"
        const val KEY_HOPS = "max_hops"

        const val KEY_HW_BUTTON = "hw_button"
        const val HW_OFF = "off"
        const val HW_HEADSET = "headset"
        const val HW_VOLUME = "volume"
        const val HW_BOTH = "both"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUDIO_ROUTE = "audio_route"
        const val KEY_HEADSET_CALL = "headset_call"
        const val KEY_HEADSET_VOX = "headset_vox"
        const val KEY_CUE_TONES = "cue_tones"
        const val KEY_PROXIMITY = "proximity_sensor"
        const val ROUTE_AUTO = "auto"
        const val ROUTE_SPEAKER = "speaker"
        const val ROUTE_EARPIECE = "earpiece"
        const val KEY_FULL_DUPLEX = "full_duplex"
        const val KEY_RELAY = "relay"
        const val KEY_OPUS = "opus"

        // Main screen state.
        const val KEY_USE_LAN = "use_lan"
        const val KEY_USE_BT = "use_bt"
        const val KEY_USE_AWARE = "use_aware"
        const val KEY_BT_PEER = "bt_peer"          // MAC address, or empty for "listen only"
    }
}
