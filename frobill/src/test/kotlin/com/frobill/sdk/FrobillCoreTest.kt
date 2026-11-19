package com.frobill.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrobillCoreTest {
    @Test
    fun `accepte une page de paiement Frobill`() {
        for (url in listOf(
            "https://pay.frobill.com/pay/pi_123ABC",
            "https://pay.frobill.com/pay/abc-DEF_9/",
            "https://PAY.FROBILL.COM/pay/abc",
            "HTTPS://pay.frobill.com/pay/abc",
            "https://pay.frobill.com:443/pay/abc",
            "https://pay.frobill.com/pay/abc?lang=fr",
        )) {
            assertEquals(url, FrobillCore.validateCheckoutUrl(url))
        }
    }

    @Test
    fun `refuse tout ce qui n'est pas une page de paiement autorisee`() {
        for (url in listOf(
            null,
            "",
            "http://pay.frobill.com/pay/abc",
            "https://evil.example/pay/abc",
            "https://pay.frobill.com.evil.example/pay/abc",
            "https://pay.frobill.com@evil.example/pay/abc",
            "https://user:pass@pay.frobill.com/pay/abc",
            "https://pay.frobill.com:8443/pay/abc",
            "https://pay.frobill.com/",
            "https://pay.frobill.com/pay/",
            "https://pay.frobill.com/pay/abc/extra",
            "https://pay.frobill.com/pay/../admin",
            "https://pay.frobill.com/pay/%2e%2e",
            "https://pay.frobill.com/autre/abc",
            "https://pay.frobill.com\\@evil.example/pay/abc",
            "https://pay.frobill.com/pay/a b",
            "https://pay.frobill.com/pay/abc\n",
            "https:/pay.frobill.com/pay/abc",
            "javascript:alert(1)",
            "monapp://frobill/retour",
            "/pay/abc",
        )) {
            assertThrows(url, FrobillInvalidCheckoutUrlException::class.java) {
                FrobillCore.validateCheckoutUrl(url)
            }
        }
    }

    @Test
    fun `hote configurable pour les tests`() {
        FrobillCore.validateCheckoutUrl("https://localhost:8443/pay/abc", "localhost:8443")
        FrobillCore.validateCheckoutUrl("https://staging.frobill.test/pay/abc", "staging.frobill.test")
        assertThrows(FrobillInvalidCheckoutUrlException::class.java) {
            FrobillCore.validateCheckoutUrl("https://pay.frobill.com/pay/abc", "staging.frobill.test")
        }
    }

    @Test
    fun `lit les quatre statuts de Frobill`() {
        for (status in listOf("succeeded", "failed", "canceled", "expired")) {
            val result = FrobillCore.parseReturnUrl(
                "monapp://frobill/retour?frobill_intent=pi_1&status=$status&reference=CMD-1042",
            )
            assertEquals(status, result.status.value)
            assertEquals("pi_1", result.intentId)
            assertEquals("CMD-1042", result.reference)
            assertNull(result.error)
        }
    }

    @Test
    fun `decode les parametres et ignore le fragment`() {
        val result = FrobillCore.parseReturnUrl(
            "monapp://frobill/retour?frobill_intent=pi_1&status=succeeded&reference=CMD%20%231042+A#fin",
        )
        assertEquals(FrobillResult(FrobillStatus.SUCCEEDED, "pi_1", "CMD #1042 A"), result)
        assertTrue(result.isSucceeded)
    }

    @Test
    fun `statut absent, inconnu ou mal encode donne unknown`() {
        assertEquals(FrobillResult(FrobillStatus.UNKNOWN), FrobillCore.parseReturnUrl("monapp://frobill/retour"))
        assertEquals(FrobillResult(FrobillStatus.UNKNOWN), FrobillCore.parseReturnUrl(null))
        assertEquals(FrobillResult(FrobillStatus.UNKNOWN), FrobillCore.parseReturnUrl("pas un lien"))
        assertEquals(
            FrobillResult(FrobillStatus.UNKNOWN),
            FrobillCore.parseReturnUrl("monapp://frobill/retour?status=closed&frobill_intent="),
        )
        assertEquals(
            FrobillResult(FrobillStatus.UNKNOWN, "pi_1"),
            FrobillCore.parseReturnUrl("monapp://frobill/retour?status=%E0%A4%A&frobill_intent=pi_1"),
        )
    }

    @Test
    fun `la premiere occurrence d'un parametre l'emporte`() {
        assertEquals(
            FrobillStatus.FAILED,
            FrobillCore.parseReturnUrl("monapp://frobill/retour?status=failed&status=succeeded").status,
        )
    }

    @Test
    fun `schema attendu`() {
        assertEquals(
            FrobillStatus.EXPIRED,
            FrobillCore.parseReturnUrl("MonApp://frobill/retour?status=expired", "monapp").status,
        )
        assertEquals(
            FrobillResult(FrobillStatus.UNKNOWN),
            FrobillCore.parseReturnUrl("autreapp://frobill/retour?status=succeeded&frobill_intent=pi_1", "monapp"),
        )
    }

    @Test
    fun `fromValue`() {
        assertEquals(FrobillStatus.CLOSED, FrobillStatus.fromValue("closed"))
        assertEquals(FrobillStatus.UNKNOWN, FrobillStatus.fromValue(null))
        assertEquals(FrobillStatus.UNKNOWN, FrobillStatus.fromValue("autre"))
    }
}
