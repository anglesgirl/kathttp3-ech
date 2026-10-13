package dev.kathttp3.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kathttp3.DnsResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import dev.kathttp3.KatHttp3Header
import dev.kathttp3.KatHttp3Request
import dev.kathttp3.ResolvedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.time.TimeSource

/** 国内四家纯数字 DoH（IP 直连，绕过移动对 DoH 域名的屏蔽）。 */
private val DOH_LIST = listOf(
    "https://223.5.5.5/dns-query",        // 阿里
    "https://223.6.6.6/dns-query",        // 阿里备用
    "https://119.29.29.29/dns-query",     // 腾讯 DNSPod
    "https://101.198.198.198/dns-query",  // 360（证书 SAN）
    "https://101.226.4.6/dns-query",      // 360 电信/移动/铁通
    "https://218.30.118.6/dns-query",     // 360 备选
    "https://140.207.198.6/dns-query",    // 360 联通备选
    "https://doh.volcdns.pub/dns-query",  // 火山引擎（域名版）
)

/** 写死 IP + 可选 ECH 配置的解析器（不查目标域名，目标域名被墙时用）。 */
private class FixedResolver(private val ip: String, private val echConfig: ByteArray?) : DnsResolver {
    override fun resolve(host: String, port: Int): List<ResolvedAddress> =
        listOf(ResolvedAddress(ip, port, echConfig))
}

private const val MAX_LOG_CHARS = 30_000

data class Ao3TestState(
    val ip: String = "104.244.46.211",
    val host: String = "archiveofourown.org",
    val echDomain: String = "crypto.cloudflare.com",
    val loading: Boolean = false,
    val log: String = "",
)

class MainViewModel : ViewModel() {
    private val mutable = MutableStateFlow(Ao3TestState())
    val state = mutable.asStateFlow()

    private var job: Job? = null

    fun setIp(value: String) = update { copy(ip = value.trim()) }
    fun setHost(value: String) = update { copy(host = value.trim()) }
    fun setEchDomain(value: String) = update { copy(echDomain = value.trim()) }

    fun clearLog() = update { copy(log = "") }

    fun runTest() {
        if (mutable.value.loading) return
        val snapshot = mutable.value
        mutable.value = snapshot.copy(loading = true, log = "")
        job = viewModelScope.launch {
            var s = Ao3TestState(snapshot.ip, snapshot.host, snapshot.echDomain, loading = true)
            s = appendLog(s, "======================================")
            s = appendLog(s, " AO3 ECH / H3 直连测试")
            s = appendLog(s, "======================================")
            s = appendLog(s, " 目标 IP  (写死): ${snapshot.ip}")
            s = appendLog(s, " 目标域名 (SNI) : ${snapshot.host}")
            s = appendLog(s, " ECH 来源域名   : ${snapshot.echDomain}")
            s = appendLog(s, "--------------------------------------")
            try {
                // 第一步：四家纯数字 DoH 拉 crypto.cloudflare.com 的 ECH 配置
                s = appendLog(s, "[1] 四家纯数字 DoH 拉取 ECH 配置")
                val (echConfig, dohLog) = withContext(Dispatchers.IO) { fetchEchConfig(snapshot) }
                s = appendLog(s, dohLog.trimEnd())
                if (echConfig == null) {
                    s = appendLog(s, "    ✗ 所有 DoH 都拿不到 ECH 配置，无法进行 ECH 测试。")
                    s = appendLog(s, "======================================")
                    mutable.value = s.copy(loading = false)
                    return@launch
                }
                s = appendLog(s, "    → 使用第一家成功的 DoH 的 ECH 配置（${echConfig.size} 字节）")
                s = appendLog(s, "--------------------------------------")

                // 第二步：H3 + ECH 直连
                s = appendLog(s, "[2] H3 + ECH 直连（QUIC 443，SNI 加密）")
                val echResult = withContext(Dispatchers.IO) {
                    runH3(snapshot.ip, snapshot.host, echConfig)
                }
                s = appendLog(s, echResult)
                s = appendLog(s, "--------------------------------------")

                // 第三步：H3 无 ECH（对比）
                s = appendLog(s, "[3] H3 无 ECH 直连（QUIC 443，明文 SNI 对比）")
                val plainResult = withContext(Dispatchers.IO) {
                    runH3(snapshot.ip, snapshot.host, null)
                }
                s = appendLog(s, plainResult)
                s = appendLog(s, "======================================")
                s = appendLog(s, " 结论：[2] 成功 = 移动可 ECH 直连 AO3；")
                s = appendLog(s, "       [3] 成功 = 移动可 QUIC 直连。")
                s = appendLog(s, "======================================")
            } catch (t: Throwable) {
                s = appendLog(s, " 异常：${t.message ?: t.javaClass.simpleName}")
            }
            mutable.value = s.copy(loading = false)
        }
    }

    /** 依次用四家 DoH 查 [echDomain] 的 HTTPS 记录，返回第一个拿到的 ECH 配置和日志。 */
    private fun fetchEchConfig(snapshot: Ao3TestState): Pair<ByteArray?, String> {
        val log = StringBuilder()
        var firstFound: ByteArray? = null
        for (doh in DOH_LIST) {
            val result = queryHttpsRecord(doh, snapshot.echDomain)
            when {
                result == null -> log.append("    ✗ $doh 请求失败（连接超时/被劫持）\n")
                result.ech != null -> {
                    if (firstFound == null) firstFound = result.ech
                    log.append("    ✓ $doh 拿到 ECH（${result.ech.size} 字节）\n")
                }
                result.httpsRecords.isEmpty() -> {
                    log.append("    ✗ $doh 返回正常但无 HTTPS 记录\n")
                }
                else -> {
                    val raw = result.httpsRecords.first().let {
                        if (it.length > 90) it.take(90) + "…" else it
                    }
                    log.append("    ✗ $doh 有 HTTPS 记录但无 ech= 参数\n")
                    log.append("      raw: $raw\n")
                }
            }
        }
        return firstFound to log.toString()
    }

    private class HttpsQueryResult(
        val ech: ByteArray?,
        val httpsRecords: List<String>,
    )

    /** 构造 DNS wire format 查询（header + question），type=HTTPS(65)。 */
    private fun buildDnsQuery(domain: String, qtype: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // ID(2) + flags(2: RD=1) + QDCOUNT=1 + ANCOUNT=0 + NSCOUNT=0 + ARCOUNT=0
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in domain.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf((qtype shr 8).toByte(), qtype.toByte(), 0x00, 0x01))
        return out.toByteArray()
    }

    /** 解析 DNS 响应的 HTTPS 记录：priority + target + params(key=5 即 ech)。 */
    private fun parseHttpsRecords(data: ByteArray): List<Pair<ByteArray?, String>> {
        val out = mutableListOf<Pair<ByteArray?, String>>()
        var off = 12
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        repeat(qd) {
            while (data[off].toInt() != 0) off++
            off += 5
        }
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        repeat(an) {
            // name：压缩指针或 labels
            if ((data[off].toInt() and 0xC0) == 0xC0) off += 2
            else {
                while (data[off].toInt() != 0) off++
                off += 1
            }
            val rtype = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            val rdlen = ((data[off + 8].toInt() and 0xFF) shl 8) or (data[off + 9].toInt() and 0xFF)
            off += 10
            val rdata = data.copyOfRange(off, off + rdlen)
            off += rdlen
            if (rtype != 65) return@repeat
            // rdata: priority(2) + target(name) + params
            var pos = 2
            val target = StringBuilder()
            while (rdata[pos].toInt() != 0) {
                val len = rdata[pos].toInt()
                pos++
                target.append(rdata.copyOfRange(pos, pos + len).toString(Charsets.US_ASCII)).append('.')
                pos += len
            }
            pos++ // root label
            var ech: ByteArray? = null
            while (pos + 4 <= rdata.size) {
                val key = ((rdata[pos].toInt() and 0xFF) shl 8) or (rdata[pos + 1].toInt() and 0xFF)
                val vlen = ((rdata[pos + 2].toInt() and 0xFF) shl 8) or (rdata[pos + 3].toInt() and 0xFF)
                if (pos + 4 + vlen > rdata.size) break
                val value = rdata.copyOfRange(pos + 4, pos + 4 + vlen)
                if (key == 5 && ech == null && value.isNotEmpty()) ech = value
                pos += 4 + vlen
            }
            val desc = "priority=${((rdata[0].toInt() and 0xFF) shl 8) or (rdata[1].toInt() and 0xFF)} " +
                "target=${target.toString().ifEmpty { "<owner>" }} " +
                "ech=${ech?.size ?: "无"}"
            out.add(ech to desc)
        }
        return out
    }

    /** 直接 GET RFC8484 DoH（dns= wire format），解析 HTTPS 记录并提取 ECH。 */
    private fun queryHttpsRecord(doh: String, domain: String): HttpsQueryResult? {
        val wire = buildDnsQuery(domain, 65)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
        val sep = if ('?' in doh) "&" else "?"
        val url = "$doh${sep}dns=$b64"
        val conn: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val data = conn.inputStream.use { it.readBytes() }
            val records = parseHttpsRecords(data)
            val firstEch = records.firstNotNullOfOrNull { it.first }
            HttpsQueryResult(firstEch, records.map { it.second })
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 发一个 H3 GET 请求，返回一行结果描述。 */
    private suspend fun runH3(ip: String, host: String, ech: ByteArray?): String {
        val client = KatHttp3Client(
            KatHttp3ClientConfig(
                resolver = FixedResolver(ip, ech),
                connectTimeoutMillis = 8_000,
                requestTimeoutMillis = 15_000,
                idleTimeoutMillis = 15_000,
            ),
        )
        return try {
            val mark = TimeSource.Monotonic.markNow()
            val response = client.execute(KatHttp3Request("GET", "https://$host/", emptyList<KatHttp3Header>(), null))
            val ms = mark.elapsedNow().inWholeMilliseconds
            "    ${if (response.status in 200..599) "✓" else "✗"} HTTP ${response.status}  协议 ${response.protocol}  耗时 ${ms}ms  大小 ${response.body.size} 字节"
        } catch (t: Throwable) {
            "    ✗ 失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            client.close()
        }
    }

    fun cancel() {
        if (!mutable.value.loading) return
        job?.cancel()
        job = null
        update { copy(loading = false, log = log + "\n（已取消）") }
    }

    override fun onCleared() {
        job?.cancel()
    }

    private fun update(transform: Ao3TestState.() -> Ao3TestState) {
        mutable.value = mutable.value.transform()
    }

    private fun appendLog(s: Ao3TestState, line: String): Ao3TestState =
        s.copy(log = (s.log + line + "\n").takeLast(MAX_LOG_CHARS))
}
