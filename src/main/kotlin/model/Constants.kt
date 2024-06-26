package model

const val RELAY_HANDLER_NAME = "relay_handler"
const val PROXY_HANDLER_NAME = "proxy_handler"
const val TROJAN_PROXY_OUTBOUND = "trojan_proxy_outbound"
const val LOG_HANDLER = "log_handler"
const val IDLE_CLOSE_HANDLER = "idle_close_handler"
const val IDLE_CHECK_HANDLER = "idle_check_handler"
const val GLOBAL_TRAFFIC_SHAPING = "global_traffic_shaping"


enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, OFF;

    companion object {
        @JvmStatic
        fun by(levelString: String) = when (levelString.lowercase()) {
            "trace" -> TRACE
            "debug" -> DEBUG
            "info" -> INFO
            "warn" -> WARN
            "error" -> ERROR
            "off" -> OFF
            else -> INFO
        }
    }

    fun toLogBackLevel(): ch.qos.logback.classic.Level = when (this) {
        TRACE -> ch.qos.logback.classic.Level.TRACE
        DEBUG -> ch.qos.logback.classic.Level.DEBUG
        INFO -> ch.qos.logback.classic.Level.INFO
        WARN -> ch.qos.logback.classic.Level.WARN
        ERROR -> ch.qos.logback.classic.Level.ERROR
        OFF -> ch.qos.logback.classic.Level.OFF
    }


}
