package interlock.relay.core.transport

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 目录里积了几千条记录时，[RequestStateStore.create] 与巡检清理各要付多少。
 *
 * 存在意义是把改动前后的差额量出来：本机（设备）读数是一条新请求的 1.5 s 里有
 * 0.9–2.0 s 花在「列一遍目录 + 把每个状态文件读一遍做 JSON 解析」上，而幂等命中
 * 那支只要 1 ms。这个基准用同一份数据布局、同一台构建机，把这两支并排打出来。
 *
 * 数据直接铺成文件（形状与 store 自己写出来的一致），不经过 create：改动前每一条
 * create 都会触发一次整目录清理，用 create 铺 5000 条就成了 O(N²)。
 *
 * 时钟固定不动：所有记录都是「刚终态、还没到 30 分钟」，于是清理这一趟不改写任何
 * 文件（实测环境里绝大多数记录也是这个状态）。要测到点改写，看
 * [RequestStateStoreTest.createForcesAPurgeWhenTheDirectoryOverflows]。
 */
class RequestStateStorePerfTest {

    private lateinit var tempDir: File
    private lateinit var stateDir: File
    private val clock = AtomicLong(1_700_000_000_000L)

    /** 与目录规模同量级的样本数：设备上观察到的读数落在 1.5k–3k，这里取更大的一档。 */
    private val recordCount = 5_000

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("request-state-perf").toFile()
        stateDir = File(tempDir, "requests").apply { mkdirs() }
        for (index in 0 until recordCount) {
            File(stateDir, "p$index.json").writeText(terminalRecordJson("p$index", clock.get()))
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun store() = RequestStateStore(stateDir, now = { clock.get() }, wallNow = { clock.get() + 7 })

    private fun timed(label: String, warmup: Int = 1, samples: Int = 5, body: () -> Unit): Long {
        repeat(warmup) { body() }
        val times = LongArray(samples)
        for (i in 0 until samples) {
            val started = System.nanoTime()
            body()
            times[i] = (System.nanoTime() - started) / 1_000_000
        }
        times.sort()
        val median = times[samples / 2]
        println("PERF $label median=${median}ms  samples=${times.joinToString(",")}")
        return median
    }

    @Test
    fun createAndSweepOnALargeStateDirectory() {
        val s = store()
        var seq = 0
        val createMs = timed("create-after-${recordCount}-records") {
            seq += 1
            assertTrue(s.create("perf$seq", "a".repeat(64), "pkg.query", 90_000L) is CreateOutcome.Created)
        }
        val sweepMs = timed("purgeExpired-after-${recordCount}-records") { s.purgeExpired(clock.get()) }
        println("PERF threshold: per-call budget is 200ms; measured create=${createMs}ms sweep=${sweepMs}ms with $recordCount records")
    }

    /** [RequestStateStore.toJson] 写出来的形状：字段齐全、末尾不带多余空白。 */
    private fun terminalRecordJson(id: String, atMs: Long): String = buildString {
        append('{')
        append("\"id\":\"").append(id).append("\",")
        append("\"digest\":\"").append("b".repeat(64)).append("\",")
        append("\"capability\":\"pkg.query\",")
        append("\"phase\":\"SUCCEEDED\",")
        append("\"createdAtMs\":").append(atMs - 5_000L).append(',')
        append("\"admittedAtMs\":").append(atMs - 4_000L).append(',')
        append("\"ttlMs\":").append(90_000L).append(',')
        append("\"mayHaveDispatched\":true,")
        append("\"terminalAtMs\":").append(atMs).append(',')
        append("\"resultJson\":\"{\\\"v\\\":1,\\\"ok\\\":true,\\\"payload\\\":\\\"")
        // 一条回包正文的量级：设备上实测每条约 1 KB，这里照抄那个体量。
        append("x".repeat(800)).append("\\\"}\",")
        append("\"cancelRequested\":false,")
        append("\"updatedAtWallMs\":").append(atMs + 7L)
        append('}')
    }
}
