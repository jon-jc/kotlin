package com.roam.core

import java.time.LocalDate
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MoneyV1(val minor: String, val currency: String = "USD") {
    fun money(): Money {
        require(minor.matches(Regex("0|[1-9][0-9]*"))) {
            "Expected non-negative integer minor units"
        }
        return Money(minor.toLong(), currency)
    }

    companion object {
        fun from(money: Money) = MoneyV1(money.minor.toString(), money.currency)
    }
}

@Serializable
enum class ReceiptStatus {
    @SerialName("confirmed") Confirmed,
    @SerialName("cancelled") Cancelled,
}

/** Versioned export shared by Android, web, and iOS consumers. No account PII or request key. */
@Serializable
data class ReceiptV1(
    @Required val version: Int = 1,
    @Required val simulated: Boolean = true,
    val confirmation: String,
    val stayId: String,
    val status: ReceiptStatus,
    val checkIn: String,
    val checkOut: String,
    val guests: Int,
    val total: MoneyV1,
    val creditApplied: MoneyV1,
    val creditReturned: MoneyV1,
    val simulatedCardAmount: MoneyV1,
) {
    fun validate() {
        require(version == 1 && simulated) { "Unsupported receipt protocol" }
        require(confirmation.isNotBlank() && confirmation.length <= 100 && stayId.isNotBlank())
        require(guests in 1..20)
        val start = LocalDate.parse(checkIn)
        val end = LocalDate.parse(checkOut)
        require(java.time.temporal.ChronoUnit.DAYS.between(start, end) in 1..28)
        require(total.money() == creditApplied.money() + simulatedCardAmount.money()) {
            "Receipt amounts do not balance"
        }
        require(
            creditReturned.money() ==
                if (status == ReceiptStatus.Cancelled) creditApplied.money() else Money(0)
        ) {
            "Incorrect credit refund"
        }
    }

    companion object {
        fun from(booking: Booking) =
            ReceiptV1(
                confirmation = booking.id,
                stayId = booking.request.stayId,
                status =
                    if (booking.cancelled) ReceiptStatus.Cancelled else ReceiptStatus.Confirmed,
                checkIn = booking.request.checkIn.toString(),
                checkOut = booking.request.checkOut.toString(),
                guests = booking.request.guests,
                total = MoneyV1.from(booking.quote.total),
                creditApplied = MoneyV1.from(booking.quote.credit),
                creditReturned =
                    MoneyV1.from(if (booking.cancelled) booking.quote.credit else Money(0)),
                simulatedCardAmount = MoneyV1.from(booking.quote.due),
            )
    }
}

object ReceiptCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(booking: Booking): String =
        ReceiptV1.from(booking).also { it.validate() }.let { json.encodeToString(it) }

    fun decode(text: String): ReceiptV1 =
        json.decodeFromString<ReceiptV1>(text).also { it.validate() }
}
