package com.PrepPro.mobile.protocol

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Frames {
    fun sendJson(out: DataOutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size)
            .array()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    fun readJson(input: DataInputStream): JSONObject {
        val header = ByteArray(4)
        input.readFully(header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(length > 0 && length <= 12 * 1024 * 1024) { "invalid frame length: $length" }

        val payload = ByteArray(length)
        input.readFully(payload)
        return JSONObject(String(payload, Charsets.UTF_8))
    }
}
