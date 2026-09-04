package fi.crewradio

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

/**
 * The settings screen: a stock preference list backed by the default SharedPreferences,
 * so [Prefs] reads whatever is typed here. Each field is checked against [SettingsRules]
 * before it is stored; a bad value is refused with a toast and the old one stays.
 *
 * Name and hop limit apply live (the activity pushes them into the engine on resume);
 * the network settings take effect the next time Connect is pressed, and the screen says so.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class Fragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            rule(Prefs.KEY_CREW_NAME, "Channel name: up to 24 characters") { SettingsRules.validCrewName(it) }
            rule(Prefs.KEY_NAME, "My name: up to 32 bytes; leave empty to use the name of this phone") { SettingsRules.validName(it) }
            rule(Prefs.KEY_GROUP, "Must be an IPv4 multicast address, 224.0.0.0 to 239.255.255.255") { SettingsRules.validGroup(it) }
            rule(Prefs.KEY_PORT, "Port must be 1024 to 65535", numeric = true) { SettingsRules.validPort(it) }
            rule(Prefs.KEY_PASSPHRASE, "8 to 63 plain ASCII characters, the same on every phone") { SettingsRules.validPassphrase(it) }
            rule(Prefs.KEY_HOPS, "Hop limit must be 1 to 16", numeric = true) { SettingsRules.validHops(it) }
        }

        /** Refuses a value the rule rejects, explaining why; optionally restricts the keyboard to digits. */
        private fun rule(key: String, why: String, numeric: Boolean = false, ok: (String) -> Boolean) {
            val pref = findPreference<EditTextPreference>(key) ?: return
            if (numeric) pref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            pref.setOnPreferenceChangeListener { _, value ->
                val accepted = ok(value.toString())
                if (!accepted) Toast.makeText(requireContext(), why, Toast.LENGTH_LONG).show()
                accepted
            }
        }
    }
}
