package com.mythos.tracker.day

/** Reads amount, direction and the other party out of a payment notification or bank SMS. */
object PaymentParser {
    data class Payment(val amount: Double, val direction: String, val party: String)

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", IC)
    private val DEBIT = Regex("\\b(paid|sent|debited|spent|payment of|transferred|withdrawn|deducted)\\b", IC)
    private val CREDIT = Regex("\\b(received|credited|refunded|refund|deposited)\\b", IC)
    private val PROMO = Regex("\\b(get|win|offer|offers|upto|up to|chance|coupon|voucher|reward|scratch|cashback)\\b", IC)
    private val OTP = Regex("\\b(otp|one time password|verification code)\\b", IC)
    private val PARTY = Regex(
        "\\b(?:to|from|at)\\s+(?:vpa\\s+|upi\\s+id\\s+)?([A-Za-z0-9@._&'/ -]{2,60}?)" +
            "(?=\\s+(?:on|via|using|successfully|is|has|ref|upi|for|with|and|txn|transaction|to|from|at)\\b|[.,!(:]|\\s*$)",
        IC,
    )
    private val NOT_PARTY = Regex("^(your|a/c|ac|account|acct|bank|card|the|you|xx)", IC)

    fun parse(text: String): Payment? {
        val t = text.replace('\n', ' ')
        if (OTP.containsMatchIn(t) && !Regex("debited|credited", IC).containsMatchIn(t)) return null
        val m = AMOUNT.find(t) ?: return null
        val d = DEBIT.find(t)
        val c = CREDIT.find(t)
        if (d == null && c == null) return null
        if (PROMO.containsMatchIn(t) && !Regex("paid|debited|credited|received", IC).containsMatchIn(t)) return null
        val direction = if (d != null && (c == null || d.range.first < c.range.first)) "debit" else "credit"
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val party = PARTY.findAll(t)
            .map { it.groupValues[1].trim().trimEnd('.', '-') }
            .firstOrNull { it.isNotBlank() && !NOT_PARTY.containsMatchIn(it) && !AMOUNT.containsMatchIn(it) }
            .orEmpty()
        return Payment(amount, direction, party)
    }
}
