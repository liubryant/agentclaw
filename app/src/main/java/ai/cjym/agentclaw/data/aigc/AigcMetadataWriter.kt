package ai.cjym.agentclaw.data.aigc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

/**
 * 人工智能生成合成内容文件元数据隐式标识写入工具。
 *
 * 依据：GB 45438-2025《网络安全技术 人工智能生成合成内容标识方法》附录E，
 * 实现方式参考 TC260《网络安全标准实践指南——人工智能生成合成内容标识方法
 * 文件元数据隐式标识 图片文件》（V1.0-202508）。
 *
 * PNG 写入 iTXt 块中的 XMP packet；文本（txt/md/csv/html 等纯文本导出）因为没有
 * 通用元数据容器，按 TC260 文本隐式标识思路用零宽字符（Zero-Width Character）编码同一份
 * AIGC JSON 后追加在正文末尾，对阅读/渲染不可见，可逐字符还原。其余格式按各自方案逐步补齐。
 */
object AigcMetadataWriter {

    /** 零宽连接符，作为隐藏载荷的起始哨兵，便于 [extractText] 定位。 */
    private const val ZW_SENTINEL = '\u200D'

    /** 零宽空格 = bit 0。 */
    private const val ZW_BIT0 = '\u200B'

    /** 零宽非连接符 = bit 1。 */
    private const val ZW_BIT1 = '\u200C'

    /** 生成合成内容服务提供者（91110108MA01KP2T5U 对应的备案主体）。 */
    private const val CONTENT_PRODUCER = "91110108MA01KP2T5U"

    /** 内容传播服务提供者（AgentClaw 发行主体备案编码）。 */
    private const val CONTENT_PROPAGATOR = "91440300MA5FYBFG1P"

    private const val AIGC_NS_URI = "http://www.tc260.org.cn/ns/AIGC/1.0/"
    private const val AIGC_NS_PREFIX = "TC260"

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Adobe XMP Specification Part 3《Storage in Files》定义的、用于在 MP4/MOV 容器中
     * 承载 XMP 的 UUID Box 扩展类型：be7acfcb-97a9-42e8-9c71-999491e3afac。
     */
    private val MP4_XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(),
        0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(),
        0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte()
    )

    /**
     * 给 PNG 字节流写入 AIGC 隐式标识（iTXt/XMP）。
     * [produceId]/[propagateId] 留空时自动生成唯一值。
     */
    fun embedPng(original: ByteArray, produceId: String? = null, propagateId: String? = null): ByteArray {
        require(original.size > 8 && original.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            "不是合法的 PNG 文件"
        }

        val xmpPacket = buildXmpPacket(
            produceId = produceId ?: UUID.randomUUID().toString(),
            propagateId = propagateId ?: UUID.randomUUID().toString()
        )
        val iTXtChunk = buildITXtChunk(xmpPacket)

        val output = ByteArrayOutputStream(original.size + iTXtChunk.size)
        output.write(original, 0, 8) // PNG 文件签名

        var pos = 8
        var inserted = false
        while (pos < original.size) {
            val length = readBigEndianInt(original, pos)
            val type = String(original, pos + 4, 4, StandardCharsets.US_ASCII)
            val chunkTotalLength = 4 + 4 + length + 4 // length(4) + type(4) + data + crc(4)
            output.write(original, pos, chunkTotalLength)
            // iTXt 紧跟在 IHDR 之后写入，符合 PNG 元数据块的常见排布约定
            if (!inserted && type == "IHDR") {
                output.write(iTXtChunk)
                inserted = true
            }
            pos += chunkTotalLength
        }

        check(inserted) { "PNG 文件缺少 IHDR 块，无法写入元数据" }
        return output.toByteArray()
    }

    /**
     * 给 MP4 字节流写入 AIGC 隐式标识：在文件末尾追加一个顶层 uuid box（扩展类型为
     * Adobe XMP-in-MP4 约定的 [MP4_XMP_UUID]），box payload 直接是 XMP packet 原始字节。
     * 顶层 box 之间允许任意顺序追加，不影响已有 ftyp/moov/mdat 的解析。
     * [produceId]/[propagateId] 留空时自动生成唯一值。
     */
    fun embedMp4(original: ByteArray, produceId: String? = null, propagateId: String? = null): ByteArray {
        require(original.size > 8 && String(original, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
            "不是合法的 MP4 文件（缺少 ftyp box）"
        }

        val xmpPacket = buildXmpPacket(
            produceId = produceId ?: UUID.randomUUID().toString(),
            propagateId = propagateId ?: UUID.randomUUID().toString()
        )
        val payload = xmpPacket.toByteArray(StandardCharsets.UTF_8)
        val boxSize = 4 + 4 + MP4_XMP_UUID.size + payload.size

        val output = ByteArrayOutputStream(original.size + boxSize)
        output.write(original)
        output.write(intToBigEndianBytes(boxSize))
        output.write("uuid".toByteArray(StandardCharsets.US_ASCII))
        output.write(MP4_XMP_UUID)
        output.write(payload)
        return output.toByteArray()
    }

    private fun buildAigcJson(produceId: String, propagateId: String): String {
        return "{\"Label\":\"1\"," +
            "\"ContentProducer\":\"$CONTENT_PRODUCER\"," +
            "\"ProduceID\":\"$produceId\"," +
            "\"ReservedCode1\":\"\"," +
            "\"ContentPropagator\":\"$CONTENT_PROPAGATOR\"," +
            "\"PropagateID\":\"$propagateId\"," +
            "\"ReservedCode2\":\"\"}"
    }

    private fun buildXmpPacket(produceId: String, propagateId: String): String {
        val json = buildAigcJson(produceId, propagateId).replace("\"", "&quot;")
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:$AIGC_NS_PREFIX=\"$AIGC_NS_URI\">" +
            "<$AIGC_NS_PREFIX:AIGC>$json</$AIGC_NS_PREFIX:AIGC>" +
            "</rdf:Description>" +
            "</rdf:RDF>" +
            "</x:xmpmeta>" +
            "<?xpacket end=\"w\"?>"
    }

    private fun buildITXtChunk(xmpPacket: String): ByteArray {
        val data = ByteArrayOutputStream()
        data.write("XML:com.adobe.xmp".toByteArray(StandardCharsets.US_ASCII))
        data.write(0) // keyword 结束符
        data.write(0) // compression flag = 0（不压缩）
        data.write(0) // compression method = 0
        data.write(0) // language tag 为空
        data.write(0) // translated keyword 为空
        data.write(xmpPacket.toByteArray(StandardCharsets.UTF_8))
        val chunkData = data.toByteArray()

        val typeAndData = ByteArrayOutputStream()
        typeAndData.write("iTXt".toByteArray(StandardCharsets.US_ASCII))
        typeAndData.write(chunkData)
        val typeAndDataBytes = typeAndData.toByteArray()

        val crc = CRC32()
        crc.update(typeAndDataBytes)

        val chunk = ByteArrayOutputStream()
        chunk.write(intToBigEndianBytes(chunkData.size))
        chunk.write(typeAndDataBytes)
        chunk.write(intToBigEndianBytes(crc.value.toInt()))
        return chunk.toByteArray()
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

    /**
     * 给纯文本内容追加 AIGC 隐式标识。纯文本没有通用元数据容器，按零宽字符方案把
     * AIGC JSON 的 UTF-8 字节逐位编码为 [ZW_BIT0]/[ZW_BIT1]，前面加 [ZW_SENTINEL] 哨兵，
     * 整段追加在正文末尾——显示/朗读/复制可见文本均不受影响，但原始字符流里携带标识。
     * [produceId]/[propagateId] 留空时自动生成唯一值。
     */
    fun embedText(original: String, produceId: String? = null, propagateId: String? = null): String {
        val json = buildAigcJson(
            produceId = produceId ?: UUID.randomUUID().toString(),
            propagateId = propagateId ?: UUID.randomUUID().toString()
        )
        val payload = buildString {
            append(ZW_SENTINEL)
            for (byte in json.toByteArray(StandardCharsets.UTF_8)) {
                for (bitIndex in 7 downTo 0) {
                    val bit = (byte.toInt() shr bitIndex) and 1
                    append(if (bit == 0) ZW_BIT0 else ZW_BIT1)
                }
            }
        }
        return original + payload
    }

    /**
     * 从 [embedText] 写入的文本中还原 AIGC JSON；未找到隐藏载荷时返回 null。
     */
    fun extractText(text: String): String? {
        val sentinelIndex = text.lastIndexOf(ZW_SENTINEL)
        if (sentinelIndex < 0) return null

        val bits = text.substring(sentinelIndex + 1)
        if (bits.isEmpty() || bits.length % 8 != 0) return null
        if (bits.any { it != ZW_BIT0 && it != ZW_BIT1 }) return null

        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < bits.length) {
            var byte = 0
            for (j in 0 until 8) {
                byte = (byte shl 1) or if (bits[i + j] == ZW_BIT1) 1 else 0
            }
            bytes.write(byte)
            i += 8
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
}
