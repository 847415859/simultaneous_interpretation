package com.yinanyi.app.models

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random

/** 模拟日语模型的发布形式：tar.bz2 里套一层目录，里面有要用的文件，也有不要的大文件。 */
class ArchiveInstallTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val files = mapOf(
        "encoder-epoch-35-avg-1.int8.onnx" to ByteArray(80_000).also { Random(1).nextBytes(it) },
        "tokens.txt" to "a 0\nb 1\n".toByteArray(),
        "encoder-epoch-35-avg-1.onnx" to ByteArray(200_000).also { Random(2).nextBytes(it) }, // 不要的 fp32 版本
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun makeArchive(corrupt: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            files.forEach { (name, content) ->
                val bytes = if (name == corrupt) content.copyOf().also { it[0] = (it[0] + 1).toByte() } else content
                tar.putArchiveEntry(TarArchiveEntry("model-dir/$name").apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private val archive = makeArchive()

    private fun pack(url: List<String>) = ModelPack(
        id = "t", label = "测试", description = "",
        archive = ArchiveSpec(
            urls = url, size = archive.size.toLong(), sha256 = sha(archive),
            entries = listOf("encoder-epoch-35-avg-1.int8.onnx", "tokens.txt").map {
                EntrySpec(it, "m/$it", files.getValue(it).size.toLong(), sha(files.getValue(it)))
            },
        ),
    )

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(archive))
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `下载压缩包后只解出需要的文件，临时文件被清掉`() {
        val root = tmp.newFolder()
        val p = pack(listOf(server.url("/a").toString()))
        assertFalse(p.isInstalled(root))

        var extracting = false
        PackInstaller().install(p, root, onExtracting = { extracting = true })

        assertTrue(extracting)
        assertTrue(p.isInstalled(root))
        assertArrayEquals(files.getValue("tokens.txt"), File(root, "m/tokens.txt").readBytes())
        assertArrayEquals(files.getValue("encoder-epoch-35-avg-1.int8.onnx"), File(root, "m/encoder-epoch-35-avg-1.int8.onnx").readBytes())
        assertFalse("不要的文件不该被解出来", File(root, "m/encoder-epoch-35-avg-1.onnx").exists())
        assertFalse("压缩包下载完应删除", File(root, "_download/t.tar.bz2").exists())
    }

    @Test fun `压缩包里的文件被改过时解压失败，不会留下半个文件`() {
        val bad = makeArchive(corrupt = "tokens.txt")
        // 压缩包整体校验用坏包自己的 SHA，才能走到解压这一步
        val p = pack(listOf(server.url("/a").toString())).let {
            it.copy(archive = it.archive!!.copy(size = bad.size.toLong(), sha256 = sha(bad)))
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(bad))
        }
        val root = tmp.newFolder()
        try {
            PackInstaller().install(p, root)
            fail("应该失败")
        } catch (_: IOException) {
        }
        assertFalse(File(root, "m/tokens.txt").exists())
        assertFalse(File(root, "m/tokens.txt.part").exists())
    }

    @Test fun `第一个镜像给了坏的压缩包时换下一个镜像`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(Buffer().write(if (request.path == "/bad") archive.copyOf().also { it[10] = 0 } else archive))
        }
        val root = tmp.newFolder()
        val p = pack(listOf(server.url("/bad").toString(), server.url("/good").toString()))
        PackInstaller().install(p, root)
        assertTrue(p.isInstalled(root))
        assertEquals(2, server.requestCount)
    }

    @Test fun `isInstalled 要求大小也对，半个文件不算已安装`() {
        val root = tmp.newFolder()
        val p = pack(listOf("http://unused"))
        File(root, "m").mkdirs()
        File(root, "m/tokens.txt").writeBytes(ByteArray(3))
        File(root, "m/encoder-epoch-35-avg-1.int8.onnx").writeBytes(ByteArray(3))
        assertFalse(p.isInstalled(root))
    }
}
