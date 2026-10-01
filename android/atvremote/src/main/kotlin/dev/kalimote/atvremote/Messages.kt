package dev.kalimote.atvremote

/**
 * Messages of the Android TV Remote Protocol v2. Field numbers follow
 * pairingmessage.proto / remotemessage.proto from the Google TV remote app.
 */
internal object Pairing {
    const val PROTOCOL_VERSION = 1
    const val STATUS = 2
    const val REQUEST = 10
    const val REQUEST_ACK = 11
    const val OPTION = 20
    const val CONFIGURATION = 30
    const val CONFIGURATION_ACK = 31
    const val SECRET = 40
    const val SECRET_ACK = 41

    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    private const val ENCODING_HEXADECIMAL = 3
    private const val ROLE_INPUT = 1

    private fun envelope(field: Int, body: ProtoWriter) = ProtoWriter()
        .varint(PROTOCOL_VERSION, 2)
        .varint(STATUS, STATUS_OK)
        .message(field, body)
        .toByteArray()

    private fun hexEncoding() = ProtoWriter().varint(1, ENCODING_HEXADECIMAL).varint(2, 6)

    fun request(clientName: String) =
        envelope(REQUEST, ProtoWriter().string(1, "atvremote").string(2, clientName))

    fun option() = envelope(OPTION, ProtoWriter().message(1, hexEncoding()).varint(3, ROLE_INPUT))

    fun configuration() =
        envelope(CONFIGURATION, ProtoWriter().message(1, hexEncoding()).varint(2, ROLE_INPUT))

    fun secret(secret: ByteArray) = envelope(SECRET, ProtoWriter().bytes(1, secret))
}

internal object Remote {
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val ERROR = 3
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    const val KEY_INJECT = 10
    const val IME_KEY_INJECT = 20
    const val IME_BATCH_EDIT = 21
    const val IME_SHOW_REQUEST = 22
    const val START = 40
    const val SET_VOLUME_LEVEL = 50
    const val APP_LINK_LAUNCH = 90

    /** KEY | IME | VOICE | POWER | VOLUME | APP_LINK feature bits. */
    const val FEATURES = 622

    private fun remote(field: Int, body: ProtoWriter) = ProtoWriter().message(field, body).toByteArray()

    fun configure(info: DeviceInfo) = remote(
        CONFIGURE,
        ProtoWriter().varint(1, FEATURES).message(
            2,
            ProtoWriter()
                .string(1, info.model)
                .string(2, info.vendor)
                .varint(3, 1)
                .string(4, "1")
                .string(5, "atvremote")
                .string(6, info.appVersion),
        ),
    )

    fun setActive() = remote(SET_ACTIVE, ProtoWriter().varint(1, FEATURES))

    fun pingResponse(value: Long) = remote(PING_RESPONSE, ProtoWriter().varint(1, value))

    fun key(code: Int, direction: Direction) =
        remote(KEY_INJECT, ProtoWriter().varint(1, code).varint(2, direction.value))

    fun appLink(url: String) = remote(APP_LINK_LAUNCH, ProtoWriter().string(1, url))

    fun imeBatchEdit(imeCounter: Int, fieldCounter: Int, text: String): ByteArray {
        val cursor = maxOf(text.length - 1, 0)
        return remote(
            IME_BATCH_EDIT,
            ProtoWriter()
                .varint(1, imeCounter)
                .varint(2, fieldCounter)
                .message(
                    3,
                    ProtoWriter().varint(1, 1).message(
                        2,
                        ProtoWriter().varint(1, cursor).varint(2, cursor).string(3, text),
                    ),
                ),
        )
    }
}

data class DeviceInfo(
    val model: String = "Kalimote",
    val vendor: String = "Kalimote",
    val appVersion: String = "1.0.0",
)

enum class Direction(val value: Int) {
    START_LONG(1),
    END_LONG(2),
    SHORT(3),
}

/** Android KeyEvent key codes accepted by the TV. */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DIGIT_0 = 7
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val ASSIST = 219
    const val MEDIA_AUDIO_TRACK = 222

    fun digit(n: Int): Int {
        require(n in 0..9)
        return DIGIT_0 + n
    }
}
