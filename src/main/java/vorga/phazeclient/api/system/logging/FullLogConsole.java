package vorga.phazeclient.api.system.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Also usable before Minecraft classes have been transformed/initialized. */
public final class FullLogConsole {
    private static final AbstractAppender APPENDER = new AbstractAppender("PhazeFullLogs", null, null,
            true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            if (!FullLog.isEnabled() || !event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return;
            FullLog.publish(event.getLevel().name(), "CONSOLE " + event.getLoggerName(),
                    event.getMessage() == null ? "" : event.getMessage().getFormattedMessage(),
                    event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR));
            if (event.getThrown() != null) FullLog.error("CONSOLE_STACK " + event.getLoggerName(), event.getThrown());
        }
    };
    private static boolean hooksInstalled;

    private FullLogConsole() {}

    public static synchronized void attach() {
        if (!APPENDER.isStarted()) {
            APPENDER.start();
            ((LoggerContext) LogManager.getContext(false)).getRootLogger().addAppender(APPENDER);
        }
        if (hooksInstalled) return;
        hooksInstalled = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            FullLog.error("UNCAUGHT thread=" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
            else error.printStackTrace(System.err);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(FullLog::stop, "phaze-full-log-shutdown"));
    }

    public static synchronized void detach() {
        if (APPENDER.isStarted()) {
            ((LoggerContext) LogManager.getContext(false)).getRootLogger().removeAppender(APPENDER);
            APPENDER.stop();
        }
    }
}
