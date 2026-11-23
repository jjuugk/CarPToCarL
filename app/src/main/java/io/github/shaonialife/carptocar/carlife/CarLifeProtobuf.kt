package io.github.shaonialife.carptocar.carlife

/** Shared bounded decoder for CarLife command protobuf payloads. */
internal object CarLifeProtobuf {
    data class Field(
        val number: Int,
        val wire: Int,
        val value: Long? = null,
        val bytes: ByteArray? = null,
    )

    data class Fields(
        val items: List<Field>,
        val error: String?,
    ) {
        fun int(number: Int): Int? = items.lastOrNull { it.number == number && it.wire == 0 }?.value?.toInt()

        fun bytes(number: Int): ByteArray? = items.lastOrNull { it.number == number && it.wire == 2 }?.bytes
    }

    fun parse(
        payload: ByteArray,
        maxFields: Int = 128,
    ): Fields {
        val fields = mutableListOf<Field>()
        var index = 0

        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(index < payload.size) { "truncated-varint" }
                val b = payload[index++].toInt() and 255
                require(i != 9 || b <= 1) { "overflow-varint" }
                value = value or ((b and 127).toLong() shl (i * 7))
                if (b and 128 == 0) return value
            }
            error("overflow-varint")
        }
        return try {
            require(payload.size <= 65535) { "oversized-command" }
            while (index < payload.size) {
                require(fields.size < maxFields.coerceIn(1, 1024)) { "too-many-fields" }
                val tag = varint()
                require(tag in 8..0xffffffffL) { "invalid-tag" }
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (wire == 0) {
                    fields += Field(number, wire, value = varint())
                } else {
                    val length =
                        when (wire) {
                            1 -> 8L
                            2 -> varint()
                            5 -> 4L
                            else -> error("unsupported-wire-$wire")
                        }
                    require(length >= 0 && length <= payload.size - index) { "truncated-field-$number" }
                    val end = index + length.toInt()
                    fields += Field(number, wire, bytes = if (wire == 2) payload.copyOfRange(index, end) else null)
                    index = end
                }
            }
            Fields(fields, null)
        } catch (e: IllegalArgumentException) {
            Fields(fields, e.message)
        } catch (e: IllegalStateException) {
            Fields(fields, e.message)
        }
    }
}
