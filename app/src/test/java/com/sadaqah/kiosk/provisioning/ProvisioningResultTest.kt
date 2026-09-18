package com.sadaqah.kiosk.provisioning

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningResultTest {

    @Test
    fun `applied result round-trips through json with every field`() {
        val result = ProvisioningResult(
            runId = "run-123",
            status = ProvisioningResult.APPLIED,
            at = "2026-09-17T00:00:00Z",
            appVersion = "1.4.0",
            installId = "device-abc",
            kioskCode = "nl-gld-arnhem-01",
            kioskName = "Arnhem — hal",
            destinationConfigured = true,
            affiliateKeyRestored = true,
            logoApplied = true,
            logoDecodable = true
        )

        val json = result.toJson()
        val parsed = Gson().fromJson(json, ProvisioningResult::class.java)

        assertEquals(result, parsed)
        assertEquals("applied", parsed.status)
    }

    @Test
    fun `failed result reports a reason and defaults everything else`() {
        val result = ProvisioningResult(
            runId = "run-456",
            status = ProvisioningResult.FAILED,
            at = "2026-09-17T00:00:00Z",
            appVersion = "1.4.0",
            reason = "wrong_password"
        )

        val json = result.toJson()

        assertEquals("failed", ProvisioningResult.FAILED)
        assertTrue(json.contains("\"reason\":\"wrong_password\""))
        assertTrue(json.contains("\"installId\":\"\""))
    }

    @Test
    fun `json exposes only the declared identity and boolean fields`() {
        val json = ProvisioningResult(
            runId = "run-789",
            status = ProvisioningResult.APPLIED,
            at = "2026-09-17T00:00:00Z",
            appVersion = "1.4.0"
        ).toJson()

        val keys = Gson().fromJson(json, Map::class.java).keys

        // Guards the contract in the class doc: this is the exact key set a
        // future field must be added to deliberately. A field slipped in to
        // carry a raw credential (rather than a boolean about it) would show
        // up here as an unexpected key, in a file Task 5's script reads over
        // adb.
        assertEquals(
            setOf(
                "runId", "status", "at", "appVersion", "installId", "kioskCode",
                "kioskName", "destinationConfigured", "affiliateKeyRestored",
                "logoApplied", "logoDecodable"
            ),
            keys
        )
    }
}
