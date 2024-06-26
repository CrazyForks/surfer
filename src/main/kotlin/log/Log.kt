import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import model.LogLevel
import model.config.Config.Configuration
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets


fun loadLogConfig() {

    // clear all appenders and present log instance
    val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    //todo log context event listener not working
    val log = logCtx.getLogger(Logger.ROOT_LOGGER_NAME)
    log.detachAndStopAllAppenders()
    log.isAdditive = false

    val logConfiguration = Configuration.log
    // set log level
    log.level = LogLevel.by(logConfiguration.level).toLogBackLevel()

    if (log.level == Level.OFF) {
        return
    }

    // default console print
    val logEncoder = PatternLayoutEncoder()
    logEncoder.context = logCtx
    logEncoder.pattern =
        logConfiguration.pattern
    logEncoder.charset = StandardCharsets.UTF_8
    logEncoder.start()

        val logConsoleAppender = ConsoleAppender<ILoggingEvent>()
    logConsoleAppender.context = logCtx
    logConsoleAppender.name = "console"
    logConsoleAppender.encoder = logEncoder
    logConsoleAppender.start()
        log.addAppender(logConsoleAppender)

    //if log configuration is null, use default configuration
    if (logConfiguration.fileName.isNotEmpty()) {
        val rollingFileAppender = RollingFileAppender<ILoggingEvent>()
        rollingFileAppender.context = logCtx
        rollingFileAppender.name = "logFile"
        rollingFileAppender.encoder = logEncoder
        rollingFileAppender.isAppend = true
        rollingFileAppender.file =
            "${logConfiguration.path.removeSuffix("/")}${File.separator}${logConfiguration.fileName.removePrefix("/")}.log"

        //init log rolling policy
        val logFilePolicy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>()
        logFilePolicy.context = logCtx
        logFilePolicy.setParent(rollingFileAppender)
        logFilePolicy.fileNamePattern =
            "${logConfiguration.path.removeSuffix("/")}${File.separator}${logConfiguration.fileName.removePrefix("/")}-%d{yyyy-MM-dd}-%i.log.zip"
        logFilePolicy.maxHistory = logConfiguration.maxHistory
        logFilePolicy.setMaxFileSize(FileSize.valueOf(logConfiguration.maxFileSize))
        logFilePolicy.start()

        rollingFileAppender.rollingPolicy = logFilePolicy
        rollingFileAppender.start()

        log.isAdditive = false
        log.level = Level.toLevel(logConfiguration.level)
            log.addAppender(rollingFileAppender)
    }

}


