package com.roam.core

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Application-facing operations shared by the offline demo and an authenticated service. */
interface CommerceGateway {
    val snapshots: Flow<AccountSnapshot>

    val catalog: Flow<List<Stay>>

    val isDemo: Boolean

    fun today(): LocalDate

    suspend fun quote(request: BookingRequest): Quote

    suspend fun refresh() {}

    suspend fun reserve(request: BookingRequest): Booking

    suspend fun cancel(key: String): Booking

    suspend fun redeem(benefit: String)

    suspend fun toggleSaved(id: String)

    suspend fun editProfile(name: String, hometown: String, bio: String)

    suspend fun setPrivacy(hometown: Boolean, activity: Boolean)
}
