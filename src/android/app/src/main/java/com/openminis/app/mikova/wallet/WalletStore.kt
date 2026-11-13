package com.openminis.app.mikova.wallet

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * [mikova/wallet] 一条流水。金额以「分」为单位：>0 收入（绿色），<0 支出（红色）。
 */
data class WalletTransaction(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val time: Long = System.currentTimeMillis(),
    val amountCents: Long,
    val note: String = "",
)

/** 钱包状态快照：余额（分）+ 流水（新的在前）。 */
data class WalletState(
    val balanceCents: Long = 0L,
    val transactions: List<WalletTransaction> = emptyList(),
)

/**
 * [mikova/wallet] 余额 + 流水本地存储，JSON 文件（file-backed）。
 *
 * 参照 MikaHost 的 file-backed 模式（AppAccessStore）：原子写（tmp + ATOMIC_MOVE）、
 * 损坏时回退空状态、MODE 由 kotlinx.serialization.json 的 DSL 手写（不引入 @Serializable
 * 类，避免与现有序列化插件用法分叉）。文件在应用 filesDir 下，进程重启不丢。
 *
 * 注意：本版没有支付通道，余额与流水是「只读台账」——充值入口置灰，金额只能由
 * 集成方（未来支付通道 / PaymentRequestCard 确认后）经 [add] 写入。不编造任何数字。
 */
class WalletStore(private val file: File) {

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<WalletState> = mutable.asStateFlow()

    /** 记一笔（同时更新余额）。amountCents 为正=收入，负=支出。 */
    @Synchronized
    fun add(name: String, amountCents: Long, note: String = ""): WalletTransaction {
        val tx = WalletTransaction(name = name, amountCents = amountCents, note = note)
        val s = mutable.value
        persist(s.copy(
            balanceCents = s.balanceCents + amountCents,
            transactions = listOf(tx) + s.transactions,
        ))
        return tx
    }

    /** 直接设余额（支付通道对账时用）。 */
    @Synchronized
    fun setBalance(balanceCents: Long) = persist(mutable.value.copy(balanceCents = balanceCents))

    /** 清空台账。 */
    @Synchronized
    fun reset() = persist(WalletState())

    private fun persist(next: WalletState) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("balanceCents", next.balanceCents)
            put("transactions", JsonArray(next.transactions.map { tx ->
                buildJsonObject {
                    put("id", tx.id)
                    put("name", tx.name)
                    put("time", tx.time)
                    put("amountCents", tx.amountCents)
                    put("note", tx.note)
                }
            }))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutable.value = next
    }

    private fun load(): WalletState {
        if (!file.exists()) return WalletState()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            val txs = o["transactions"]?.jsonArray?.map { el ->
                val t = el.jsonObject
                WalletTransaction(
                    id = t["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                    name = t["name"]?.jsonPrimitive?.content.orEmpty(),
                    time = t["time"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                    amountCents = t["amountCents"]?.jsonPrimitive?.long ?: 0L,
                    note = t["note"]?.jsonPrimitive?.content.orEmpty(),
                )
            } ?: emptyList()
            WalletState(
                balanceCents = o["balanceCents"]?.jsonPrimitive?.long ?: 0L,
                transactions = txs,
            )
        }.getOrElse { WalletState() }
    }

    companion object {
        fun get(context: Context): WalletStore =
            WalletStore(File(context.applicationContext.filesDir, "mikova-wallet.json"))
    }
}
