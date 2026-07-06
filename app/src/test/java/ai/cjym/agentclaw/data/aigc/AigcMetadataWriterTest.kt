package ai.cjym.agentclaw.data.aigc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.CRC32

class AigcMetadataWriterTest {

    /** 一张手工构造的 4x4 红色 PNG（避免依赖 java.awt/ImageIO，纯字节级测试）。 */
    private fun samplePng(): ByteArray {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg=="
        return Base64.getDecoder().decode(base64)
    }

    /** 一段手工构造的、只有 ftyp box 的最小 MP4 顶层结构（纯字节级测试，不依赖真实可播放视频）。 */
    private fun sampleMp4(): ByteArray {
        val majorBrand = "isom".toByteArray(Charsets.US_ASCII)
        val minorVersion = byteArrayOf(0, 0, 2, 0)
        val compatibleBrands = "isom".toByteArray(Charsets.US_ASCII)
        val data = majorBrand + minorVersion + compatibleBrands
        val size = 4 + 4 + data.size
        val out = java.io.ByteArrayOutputStream()
        out.write(intToBigEndianBytes(size))
        out.write("ftyp".toByteArray(Charsets.US_ASCII))
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `embedPng inserts a structurally valid iTXt chunk with AIGC payload`() {
        val original = samplePng()
        val watermarked = AigcMetadataWriter.embedPng(
            original,
            produceId = "test-produce-id",
            propagateId = "test-propagate-id"
        )

        assertTrue("输出体积应该比原图更大", watermarked.size > original.size)

        val chunks = parseChunks(watermarked)
        val iTXt = chunks.firstOrNull { it.type == "iTXt" }
        assertTrue("应存在 iTXt 块", iTXt != null)

        val text = String(iTXt!!.data, Charsets.UTF_8)
        assertTrue("iTXt 应包含 Adobe XMP 关键字", text.startsWith("XML:com.adobe.xmp"))
        assertTrue("XMP packet 应包含 TC260 命名空间", text.contains("http://www.tc260.org.cn/ns/AIGC/1.0/"))
        assertTrue("XMP packet 应包含 ContentProducer", text.contains("91110108MA01KP2T5U"))
        assertTrue("XMP packet 应包含 ContentPropagator", text.contains("91440300MA5FYBFG1P"))
        assertTrue("XMP packet 应包含 ProduceID", text.contains("test-produce-id"))
        assertTrue("XMP packet 应包含 PropagateID", text.contains("test-propagate-id"))
        assertTrue("Label 应为 1（属于AI生成）", text.contains("Label&quot;:&quot;1"))

        // 校验每个块的 CRC，确认输出文件没有因为插入操作而损坏
        for (chunk in chunks) {
            val crc = CRC32()
            crc.update(chunk.type.toByteArray(Charsets.US_ASCII))
            crc.update(chunk.data)
            assertEquals("${chunk.type} 块 CRC 应正确", crc.value, chunk.crc)
        }

        // 落盘一份，方便人工用 exiftool / 文件属性面板核对truth
        val outDir = File("build/aigc-test-output").apply { mkdirs() }
        File(outDir, "sample_with_aigc.png").writeBytes(watermarked)
    }

    @Test
    fun `embedPng output matches the ID style declared in the signed Huawei letter`() {
        // ArtifactExportRepository 里实际生成的格式：agentclaw_artifact_xxxxxxxxxx / agentclaw_msg_xxxxxxxxxx，
        // 对齐已盖章提交的《人工智能生成合成内容文件元数据隐式标识说明函》里的示例风格。
        val watermarked = AigcMetadataWriter.embedPng(
            samplePng(),
            produceId = "agentclaw_artifact_8f3a2c91d6",
            propagateId = "agentclaw_msg_c93d7e21f5"
        )

        val text = String(parseChunks(watermarked).first { it.type == "iTXt" }.data, Charsets.UTF_8)
        assertTrue(text.contains("agentclaw_artifact_8f3a2c91d6"))
        assertTrue(text.contains("agentclaw_msg_c93d7e21f5"))

        val outDir = File("build/aigc-test-output").apply { mkdirs() }
        File(outDir, "sample_with_aigc_real_id_format.png").writeBytes(watermarked)
    }

    @Test
    fun `embedText appends an invisible payload that round-trips through extractText`() {
        val visibleText = "已收到你的需求，这是 AgentClaw 根据 glm-4.7 生成的回复内容。"
        val watermarked = AigcMetadataWriter.embedText(
            visibleText,
            produceId = "test-produce-id",
            propagateId = "test-propagate-id"
        )

        assertTrue("追加隐藏载荷后可见前缀应保持不变", watermarked.startsWith(visibleText))
        assertTrue("追加后长度应变长（隐藏字符也占 length）", watermarked.length > visibleText.length)

        val extracted = AigcMetadataWriter.extractText(watermarked)
        assertTrue("应能还原出隐藏的 AIGC JSON", extracted != null)
        assertTrue("JSON 应包含 ContentProducer", extracted!!.contains("91110108MA01KP2T5U"))
        assertTrue("JSON 应包含 ContentPropagator", extracted.contains("91440300MA5FYBFG1P"))
        assertTrue("JSON 应包含 ProduceID", extracted.contains("test-produce-id"))
        assertTrue("JSON 应包含 PropagateID", extracted.contains("test-propagate-id"))
        assertTrue("Label 应为 1（属于AIGC）", extracted.contains("\"Label\":\"1\""))

        assertTrue("未写入隐藏载荷的普通文本应返回 null", AigcMetadataWriter.extractText(visibleText) == null)
    }

    @Test
    fun `embedText output matches the ID style declared in the signed Huawei letter, real sample for evidence screenshot`() {
        // 对齐 ArtifactExportRepository 里文本导出实际生成的格式：
        // agentclaw_artifact_xxxxxxxxxx / agentclaw_msg_xxxxxxxxxx。
        val visibleText = "已收到你的需求，这是 AgentClaw 根据 glm-4.7 生成并导出为 .md 的回复内容。"
        val produceId = "agentclaw_artifact_8f3a2c91d6"
        val propagateId = "agentclaw_msg_c93d7e21f5"
        val watermarked = AigcMetadataWriter.embedText(visibleText, produceId = produceId, propagateId = propagateId)

        val extracted = AigcMetadataWriter.extractText(watermarked)
        assertTrue(extracted != null)
        assertTrue(extracted!!.contains(produceId))
        assertTrue(extracted.contains(propagateId))

        val outDir = File("build/aigc-test-output").apply { mkdirs() }
        File(outDir, "sample_with_aigc_real_id_format.md").writeText(watermarked, Charsets.UTF_8)
        File(outDir, "sample_with_aigc_real_id_format.extracted.json").writeText(extracted, Charsets.UTF_8)
    }

    @Test
    fun `embedMp4 appends a structurally valid uuid XMP box with AIGC payload`() {
        val original = sampleMp4()
        val watermarked = AigcMetadataWriter.embedMp4(
            original,
            produceId = "test-produce-id",
            propagateId = "test-propagate-id"
        )

        assertTrue("输出体积应该比原文件更大", watermarked.size > original.size)
        assertTrue(
            "原 ftyp box 应原样保留在文件头",
            watermarked.copyOfRange(0, original.size).contentEquals(original)
        )

        val box = parseTopLevelBoxes(watermarked).firstOrNull { it.type == "uuid" }
        assertTrue("应存在追加的 uuid box", box != null)
        assertTrue(
            "uuid box 扩展类型应为 Adobe XMP-in-MP4 约定值",
            box!!.data.copyOfRange(0, 16).contentEquals(mp4XmpUuid())
        )

        val xmpPacket = String(box.data.copyOfRange(16, box.data.size), Charsets.UTF_8)
        assertTrue("XMP packet 应包含 TC260 命名空间", xmpPacket.contains("http://www.tc260.org.cn/ns/AIGC/1.0/"))
        assertTrue("XMP packet 应包含 ContentProducer", xmpPacket.contains("91110108MA01KP2T5U"))
        assertTrue("XMP packet 应包含 ContentPropagator", xmpPacket.contains("91440300MA5FYBFG1P"))
        assertTrue("XMP packet 应包含 ProduceID", xmpPacket.contains("test-produce-id"))
        assertTrue("XMP packet 应包含 PropagateID", xmpPacket.contains("test-propagate-id"))
    }

    @Test
    fun `embedMp4 on the real GLM-generated video produces a real sample for the evidence screenshot`() {
        val sourceFile = File("/Users/liuzheng/Desktop/资质材料汇总/glm生成视频文件.mp4")
        org.junit.Assume.assumeTrue("真实 GLM 视频样例文件不存在，跳过", sourceFile.exists())

        val original = sourceFile.readBytes()
        val produceId = "agentclaw_artifact_8f3a2c91d6"
        val propagateId = "agentclaw_msg_c93d7e21f5"
        val watermarked = AigcMetadataWriter.embedMp4(original, produceId = produceId, propagateId = propagateId)

        val box = parseTopLevelBoxes(watermarked).first { it.type == "uuid" }
        val xmpPacket = String(box.data.copyOfRange(16, box.data.size), Charsets.UTF_8)
        assertTrue(xmpPacket.contains(produceId))
        assertTrue(xmpPacket.contains(propagateId))

        val outDir = File("build/aigc-test-output").apply { mkdirs() }
        File(outDir, "glm_video_with_aigc.mp4").writeBytes(watermarked)
        File(outDir, "glm_video_with_aigc.xmp.txt").writeText(xmpPacket, Charsets.UTF_8)
    }

    private fun mp4XmpUuid(): ByteArray = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(),
        0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(),
        0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte()
    )

    private data class Mp4Box(val type: String, val data: ByteArray)

    /** 只解析顶层 box（size+type+data 顺序排列），够用于校验本文件追加的 uuid box。 */
    private fun parseTopLevelBoxes(mp4: ByteArray): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var pos = 0
        while (pos + 8 <= mp4.size) {
            val size = readBigEndianInt(mp4, pos)
            if (size < 8 || pos + size > mp4.size) break
            val type = String(mp4, pos + 4, 4, Charsets.US_ASCII)
            val data = mp4.copyOfRange(pos + 8, pos + size)
            boxes.add(Mp4Box(type, data))
            pos += size
        }
        return boxes
    }

    private data class PngChunk(val type: String, val data: ByteArray, val crc: Long)

    private fun parseChunks(png: ByteArray): List<PngChunk> {
        val chunks = mutableListOf<PngChunk>()
        var pos = 8
        while (pos < png.size) {
            val length = readBigEndianInt(png, pos)
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val data = png.copyOfRange(dataStart, dataStart + length)
            val crc = readBigEndianInt(png, dataStart + length).toLong() and 0xFFFFFFFFL
            chunks.add(PngChunk(type, data, crc))
            pos = dataStart + length + 4
        }
        return chunks
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun intToBigEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}
