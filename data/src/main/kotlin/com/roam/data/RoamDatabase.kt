package com.roam.data

import android.content.Context
import androidx.room.*
import com.roam.core.*
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "accounts")
data class AccountRecord(
    @PrimaryKey val id: Int = 1,
    val name: String = "Alex Morgan",
    val hometown: String = "San Francisco, CA",
    val bio: String = "Collecting moments, not things.",
    val shareHometown: Boolean = true,
    val shareActivity: Boolean = false,
    val balance: Long = 8500,
    val saved: String = "",
    val redeemed: String = "",
)

@Entity(
    tableName = "bookings",
    indices = [Index(value = ["requestKey"], unique = true), Index(value = ["createdAt"])],
)
data class BookingRecord(
    @PrimaryKey val id: String,
    val requestKey: String,
    val stayId: String,
    val checkIn: String,
    val checkOut: String,
    val guests: Int,
    val useCredit: Boolean,
    val nights: Long,
    val subtotal: Long,
    val fee: Long,
    val total: Long,
    val credit: Long,
    val due: Long,
    val createdAt: Long,
    val cancelled: Boolean,
)

@Entity(
    tableName = "ledger",
    indices = [Index(value = ["createdAt"]), Index(value = ["bookingId"])],
)
data class LedgerRecord(
    @PrimaryKey val id: String,
    val title: String,
    val amount: Long,
    val createdAt: Long,
    val bookingId: String?,
)

@Dao
interface RoamDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seed(account: AccountRecord)

    @Query("SELECT * FROM accounts WHERE id = 1") suspend fun account(): AccountRecord

    @Upsert suspend fun save(account: AccountRecord)

    @Query("SELECT * FROM bookings WHERE requestKey = :key")
    suspend fun booking(key: String): BookingRecord?

    @Insert suspend fun insert(booking: BookingRecord)

    @Update suspend fun update(booking: BookingRecord)

    @Insert suspend fun append(entry: LedgerRecord)

    @Query("SELECT * FROM bookings ORDER BY createdAt DESC, id")
    suspend fun bookings(): List<BookingRecord>

    @Query("SELECT * FROM ledger ORDER BY createdAt DESC, id")
    suspend fun ledger(): List<LedgerRecord>
}

@Database(
    entities = [AccountRecord::class, BookingRecord::class, LedgerRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class RoamDatabase : RoomDatabase() {
    abstract fun dao(): RoamDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, RoamDatabase::class.java, "roam.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

class RoomAccountStore(private val db: RoamDatabase) : AccountStore {
    private val dao = db.dao()

    private suspend fun seed() {
        dao.seed(AccountRecord())
    }

    override fun observe(): Flow<AccountSnapshot> = flow {
        db.withTransaction { seed() }
        emitAll(
            db.invalidationTracker.createFlow("accounts", "bookings", "ledger").map {
                db.withTransaction {
                    AccountSnapshot(
                        dao.account().domain(),
                        dao.bookings().map { it.domain() },
                        dao.ledger().map {
                            LedgerEntry(
                                it.id,
                                it.title,
                                Money(it.amount),
                                it.createdAt,
                                it.bookingId,
                            )
                        },
                    )
                }
            }
        )
    }

    override suspend fun <T> transaction(block: suspend AccountTransaction.() -> T): T =
        db.withTransaction {
            seed()
            block(
                object : AccountTransaction {
                    override suspend fun account() = dao.account().domain()

                    override suspend fun save(account: Account) {
                        dao.save(account.record())
                    }

                    override suspend fun booking(key: String) = dao.booking(key)?.domain()

                    override suspend fun insert(booking: Booking) {
                        dao.insert(booking.record())
                    }

                    override suspend fun update(booking: Booking) {
                        dao.update(booking.record())
                    }

                    override suspend fun append(entry: LedgerEntry) {
                        dao.append(
                            LedgerRecord(
                                entry.id,
                                entry.title,
                                entry.amount.minor,
                                entry.createdAt,
                                entry.bookingId,
                            )
                        )
                    }
                }
            )
        }
}

private fun String.ids(): Set<String> = split(',').filter(String::isNotBlank).toSet()

private fun AccountRecord.domain() =
    Account(
        Profile(name, hometown, bio, shareHometown, shareActivity),
        Money(balance),
        saved.ids(),
        redeemed.ids(),
    )

private fun Account.record() =
    AccountRecord(
        name = profile.name,
        hometown = profile.hometown,
        bio = profile.bio,
        shareHometown = profile.shareHometown,
        shareActivity = profile.shareActivity,
        balance = balance.minor,
        saved = saved.sorted().joinToString(","),
        redeemed = redeemed.sorted().joinToString(","),
    )

private fun BookingRecord.domain() =
    Booking(
        id,
        BookingRequest(
            requestKey,
            stayId,
            LocalDate.parse(checkIn),
            LocalDate.parse(checkOut),
            guests,
            useCredit,
        ),
        Quote(nights, Money(subtotal), Money(fee), Money(total), Money(credit), Money(due)),
        createdAt,
        cancelled,
    )

private fun Booking.record() =
    BookingRecord(
        id,
        request.key,
        request.stayId,
        request.checkIn.toString(),
        request.checkOut.toString(),
        request.guests,
        request.useCredit,
        quote.nights,
        quote.subtotal.minor,
        quote.serviceFee.minor,
        quote.total.minor,
        quote.credit.minor,
        quote.due.minor,
        createdAt,
        cancelled,
    )
