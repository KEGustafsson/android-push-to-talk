package fi.crewradio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun nameFitsAHello() {
        assertTrue(SettingsRules.validName(""))                      // empty = device name
        assertTrue(SettingsRules.validName("  Skipper S25  "))
        assertTrue(SettingsRules.validName("ä".repeat(16)))          // 32 bytes exactly
        assertFalse(SettingsRules.validName("ä".repeat(17)))         // 34 bytes
        assertFalse(SettingsRules.validName("two\nlines"))
    }

    @Test
    fun groupMustBeIpv4Multicast() {
        assertTrue(SettingsRules.validGroup("239.255.42.1"))
        assertTrue(SettingsRules.validGroup(" 224.0.0.1 "))
        assertFalse(SettingsRules.validGroup("192.168.0.1"))          // unicast
        assertFalse(SettingsRules.validGroup("240.0.0.1"))            // reserved, past the multicast block
        assertFalse(SettingsRules.validGroup("239.255.42"))
        assertFalse(SettingsRules.validGroup("239.255.42.256"))
        assertFalse(SettingsRules.validGroup("ff02::1"))              // IPv6 is not what LanTransport joins
    }

    @Test
    fun portIsUnprivileged() {
        assertTrue(SettingsRules.validPort("1024"))
        assertTrue(SettingsRules.validPort("65535"))
        assertFalse(SettingsRules.validPort("1023"))
        assertFalse(SettingsRules.validPort("65536"))
        assertFalse(SettingsRules.validPort("port"))
    }

    @Test
    fun passphraseIsWhatAwareAccepts() {
        assertTrue(SettingsRules.validPassphrase("crew-radio"))
        assertTrue(SettingsRules.validPassphrase("12345678"))
        assertFalse(SettingsRules.validPassphrase("1234567"))         // too short
        assertFalse(SettingsRules.validPassphrase("x".repeat(64)))    // too long
        assertFalse(SettingsRules.validPassphrase("salasana ääkkösillä"))   // not ASCII
    }

    @Test
    fun crewNameStaysOnOneLine() {
        assertTrue(SettingsRules.validCrewName(""))
        assertTrue(SettingsRules.validCrewName(" Skipper "))
        assertTrue(SettingsRules.validCrewName("x".repeat(24)))
        assertFalse(SettingsRules.validCrewName("x".repeat(25)))
        assertFalse(SettingsRules.validCrewName("Crew\nTwo"))
    }

    @Test
    fun hopsAreBounded() {
        assertTrue(SettingsRules.validHops("1"))
        assertTrue(SettingsRules.validHops("16"))
        assertFalse(SettingsRules.validHops("0"))
        assertFalse(SettingsRules.validHops("17"))
        assertFalse(SettingsRules.validHops(""))
    }
}
