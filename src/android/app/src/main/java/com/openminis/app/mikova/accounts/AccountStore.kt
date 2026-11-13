package com.openminis.app.mikova.accounts

import android.content.Context
import com.openminis.app.mibot.vault.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * 连接账号的本地存储 —— 与 MikaHost 的 [com.mibot.settings.AppAccessStore] 一样的
 * file-backed 方式：纯本地 JSON 文件、进程内 [StateFlow] 观察读写、原子落盘
 * （写 .tmp 再 rename），没有任何网络同步。
 *
 * 与 vault 的区别：vault 是「一个账号一条记录」的通用台账；这里是「按应用分组、
 * 一个应用一个文件」的连接台账（`filesDir/mikova/accounts/<serviceId>.json`），
 * 专门服务账号连接中心：记录每个已连接应用下的多个账号（同应用多账号，如「再
 * 添加一个 Gmail 账号」）与其授权范围 / 连接时间。
 *
 * 秘密复用本体 vault 的 Keystore AES-GCM（[VaultCrypto]）：明文只在加解密瞬间存在，
 * 落盘永远是 base64(iv):base64(ct)。
 */
class AccountStore(context: Context) {

    private val ctx = context.applicationContext
    private val dir: File = File(ctx.filesDir, "mikova/accounts")
    private val lock = Any()

    /** serviceId -> 该应用的账号列表（内存态，进程内唯一真源）。 */
    private val byService = MutableStateFlow(loadAll())

    /** 扁平化的全部已连接账号（观察用）。 */
    private val flat = MutableStateFlow(byService.value.values.flatten())

    val accounts: StateFlow<List<ConnectedAccount>> = flat.asStateFlow()

    private fun fileFor(serviceId: String) = File(dir, "$serviceId.json")

    private fun loadAll(): Map<String, List<ConnectedAccount>> {
        if (!dir.exists()) return emptyMap()
        val map = LinkedHashMap<String, List<ConnectedAccount>>()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { f ->
            val sid = f.name.removeSuffix(".json")
            val list = runCatching {
                JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) } }
                    .map(ConnectedAccount::fromJson)
            }.getOrDefault(emptyList())
            map[sid] = list
        }
        return map
    }

    /**
     * 落盘 + 更新内存。只写受影响的那个应用文件（per-app JSON），
     * 然后同步扁平化 flow。写入在锁内完成，避免并发写同一文件。
     */
    private fun persistService(serviceId: String, list: List<ConnectedAccount>) {
        synchronized(lock) {
            byService.value = (byService.value - serviceId) + (serviceId to list)
            flat.value = byService.value.values.flatten()
            dir.mkdirs()
            val f = fileFor(serviceId)
            val tmp = File(dir, f.name + ".tmp")
            val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    /** 该应用下的已连接账号（可能多个）。 */
    fun accountsFor(serviceId: String): List<ConnectedAccount> = byService.value[serviceId].orEmpty()

    fun get(id: String): ConnectedAccount? = flat.value.firstOrNull { it.id == id }

    fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 8)

    /** 加密一个秘密，返回可落盘的密文（base64(iv):base64(ct)）。 */
    fun seal(secret: String): String = VaultCrypto.encrypt(secret)

    /** 仅 oauth / 状态校验内部使用的解密入口；对外只暴露「是否有秘密」。 */
    internal fun revealSecret(account: ConnectedAccount): String? =
        account.secretEnc?.let { runCatching { VaultCrypto.decrypt(it) }.getOrNull() }

    /** 新增一条已连接账号（重复调用即「同应用多账号」）。 */
    fun add(account: ConnectedAccount): ConnectedAccount {
        persistService(account.serviceId, accountsFor(account.serviceId) + account)
        return account
    }

    /** 用 [transform] 更新某条账号。 */
    fun update(id: String, transform: (ConnectedAccount) -> ConnectedAccount): ConnectedAccount? {
        val cur = get(id) ?: return null
        val next = transform(cur)
        persistService(next.serviceId, accountsFor(next.serviceId).map { if (it.id == id) next else it })
        return next
    }

    /** 断开（删除）一条账号。 */
    fun remove(id: String) {
        val cur = get(id) ?: return
        persistService(cur.serviceId, accountsFor(cur.serviceId).filterNot { it.id == id })
    }

    /** 该应用是否已连接（任一账号存在即算已连接）。 */
    fun isConnected(serviceId: String): Boolean = accountsFor(serviceId).isNotEmpty()

    companion object {
        /** 供 OAuthBroker / 各 Screen 共用同一个进程内实例。 */
        @Volatile private var shared: AccountStore? = null

        fun shared(context: Context): AccountStore = shared ?: synchronized(this) {
            shared ?: AccountStore(context).also { shared = it }
        }
    }
}
