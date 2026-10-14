package com.yinanyi.app.models

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Random

class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val data = ByteArray(300_000).also { Random(42).nextBytes(it) }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val downloader = Downloader()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    /** 支持 Range 的服务端。 */
    private fun serveWithRange(content: ByteArray = data) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == null) return MockResponse().setBody(Buffer().write(content))
                val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    .setBody(Buffer().write(content.copyOfRange(start, content.size)))
            }
        }
    }

    @Test fun `下载完成后文件内容正确，且不留 part 文件`() {
        serveWithRange()
        val dest = File(tmp.root, "a/b.bin")
        downloader.download(listOf(url("/f")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun `已有 part 文件时从断点继续，只请求剩下的部分`() {
        serveWithRange()
        val dest = File(tmp.root, "x.bin")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 120_000))
        downloader.download(listOf(url("/f")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
        assertEquals("bytes=120000-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `服务端不支持续传时从头下载`() {
        // 永远返回 200 和完整内容，忽略 Range
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(data))
        }
        val dest = File(tmp.root, "x.bin")
        File(dest.path + ".part").writeBytes(ByteArray(50_000) { 7 }) // 垃圾前缀不能混进去
        downloader.download(listOf(url("/f")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun `第一个源失败时换下一个源`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/bad") MockResponse().setResponseCode(503)
                else MockResponse().setBody(Buffer().write(data))
        }
        val dest = File(tmp.root, "x.bin")
        downloader.download(listOf(url("/bad"), url("/good")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun `内容被篡改（校验不过）时丢弃并换源`() {
        val tampered = data.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(Buffer().write(if (request.path == "/evil") tampered else data))
        }
        val dest = File(tmp.root, "x.bin")
        downloader.download(listOf(url("/evil"), url("/good")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun `所有源的内容都校验不过时抛出失败，且不会留下坏文件`() {
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        serveWithRange(tampered)
        val dest = File(tmp.root, "x.bin")
        try {
            downloader.download(listOf(url("/a"), url("/b")), dest, data.size.toLong(), sha)
            fail("应该失败")
        } catch (e: DownloadFailed) {
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".part").exists())
        }
    }

    @Test fun `取消后保留已下载的部分，再次下载能接着来`() {
        serveWithRange()
        val dest = File(tmp.root, "x.bin")
        var calls = 0
        try {
            downloader.download(
                listOf(url("/f")), dest, data.size.toLong(), sha,
                onProgress = {},
                cancelled = { ++calls > 3 }, // 读几块之后取消
            )
            fail("应该被取消")
        } catch (_: DownloadCancelled) {
        }
        val part = File(dest.path + ".part")
        assertTrue("取消后应保留 part", part.exists() && part.length() in 1 until data.size.toLong())
        assertFalse(dest.exists())

        downloader.download(listOf(url("/f")), dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun `目标文件已经完整时不发任何请求`() {
        val dest = File(tmp.root, "x.bin").also { it.writeBytes(data) }
        downloader.download(listOf(url("/f")), dest, data.size.toLong(), sha)
        assertEquals(0, server.requestCount)
    }

    @Test fun `进度回调不会倒退，最后等于总大小`() {
        serveWithRange()
        val seen = mutableListOf<Long>()
        downloader.download(listOf(url("/f")), File(tmp.root, "x.bin"), data.size.toLong(), sha, onProgress = { seen += it })
        assertEquals(data.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }
}
