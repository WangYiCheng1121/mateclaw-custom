package vip.mate.log.platform;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 平台日志上报器
 * <p>
 * 在 Spring 上下文启动后，向 Logback 动态注册一个自定义 Appender，
 * 拦截指定级别以上的日志事件，通过异步队列缓冲后批量上报到平台端。
 * <p>
 * 设计要点：
 * 1. 使用 LinkedBlockingQueue 缓冲日志，避免阻塞业务线程
 * 2. 守护线程定时批量刷新，或达到批量大小时立即触发
 * 3. 上报失败不影响本地日志记录，仅在 debug 级别打印异常
 * 4. 过滤自身产生的日志（PlatformLogClient / PlatformLogReporter），避免递归
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformLogReporter {

    private final PlatformLogProperties logProperties;
    private final PlatformLogClient platformLogClient;

    /** 异步日志缓冲队列 */
    private BlockingQueue<LogEntry> queue;

    /** 定时刷新调度器 */
    private ScheduledExecutorService scheduler;

    /** 运行标志 */
    private volatile boolean running = false;

    /** 动态注册的 Logback Appender 引用（用于销毁时移除） */
    private PlatformReportAppender registeredAppender;

    @PostConstruct
    public void start() {
        if (!logProperties.isEnabled()) {
            log.info("[PlatformLog] 日志上报已禁用");
            return;
        }

        this.queue = new LinkedBlockingQueue<>(logProperties.getQueueCapacity());
        this.running = true;

        // 注册 Logback Appender
        registerLogbackAppender();

        // 启动定时刷新线程
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-log-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush,
                logProperties.getFlushIntervalMs(),
                logProperties.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS);

        log.info("[PlatformLog] 日志上报已启动，minLevel={}, subsystem={}, batchSize={}, flushInterval={}ms",
                logProperties.getMinLevel(), logProperties.getSubsystem(),
                logProperties.getBatchSize(), logProperties.getFlushIntervalMs());
    }

    @PreDestroy
    public void stop() {
        running = false;

        // 移除 Logback Appender
        unregisterLogbackAppender();

        // 关闭调度器前最后一次刷新
        flush();

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 将队列中的日志批量上报到平台
     */
    private void flush() {
        if (queue == null || queue.isEmpty()) {
            return;
        }

        List<LogEntry> batch = new ArrayList<>(logProperties.getBatchSize());
        queue.drainTo(batch, logProperties.getBatchSize());

        for (LogEntry entry : batch) {
            try {
                boolean success = platformLogClient.reportLog(entry.level, entry.detail, entry.logTime);
                if (!success && logProperties.getRetryCount() > 0) {
                    // 简单重试
                    for (int i = 0; i < logProperties.getRetryCount() && !success; i++) {
                        Thread.sleep(500L * (i + 1));
                        success = platformLogClient.reportLog(entry.level, entry.detail, entry.logTime);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 上报失败不影响整体流程
            }
        }
    }

    /**
     * 向 Logback 动态注册自定义 Appender
     */
    private void registerLogbackAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        registeredAppender = new PlatformReportAppender();
        registeredAppender.setContext(context);
        registeredAppender.setName("PLATFORM_REPORT");
        registeredAppender.start();

        rootLogger.addAppender(registeredAppender);
    }

    /**
     * 移除动态注册的 Logback Appender
     */
    private void unregisterLogbackAppender() {
        if (registeredAppender != null) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.detachAppender(registeredAppender);
            registeredAppender.stop();
        }
    }

    /**
     * 解析配置的最低上报级别
     */
    private Level resolveMinLevel() {
        String levelStr = logProperties.getMinLevel();
        if (levelStr == null || levelStr.isBlank()) {
            return Level.WARN;
        }
        return Level.toLevel(levelStr.toUpperCase(), Level.WARN);
    }

    /**
     * 将 Logback Level 转为平台端接受的字符串
     */
    private String toLevelString(Level level) {
        if (Level.ERROR.equals(level)) {
            return "error";
        } else if (Level.WARN.equals(level)) {
            return "warn";
        } else {
            return "info";
        }
    }

    // ==================== 内部类 ====================

    /**
     * 日志条目（队列中缓冲的简化数据）
     */
    private static class LogEntry {
        final String level;
        final String detail;
        final LocalDateTime logTime;

        LogEntry(String level, String detail, LocalDateTime logTime) {
            this.level = level;
            this.detail = detail;
            this.logTime = logTime;
        }
    }

    /**
     * 自定义 Logback Appender
     * <p>
     * 拦截日志事件，过滤后放入队列。
     * 通过内部类实现，直接访问外层 PlatformLogReporter 的 queue 和配置。
     */
    private class PlatformReportAppender extends AppenderBase<ILoggingEvent> {

        /** 需要跳过的 logger 前缀（避免自身日志递归） */
        private static final String SELF_LOGGER_PREFIX = "vip.mate.log.platform";

        @Override
        protected void append(ILoggingEvent event) {
            if (!running) {
                return;
            }

            // 过滤：跳过自身产生的日志，避免递归
            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith(SELF_LOGGER_PREFIX)) {
                return;
            }

            // 过滤：低于最低上报级别的日志不处理
            Level minLevel = resolveMinLevel();
            if (event.getLevel().toInt() < minLevel.toInt()) {
                return;
            }

            // 构造日志详情：[logger] message + 异常信息
            StringBuilder detail = new StringBuilder();
            detail.append("[").append(event.getLoggerName()).append("] ");
            detail.append(event.getFormattedMessage());

            // 附加异常堆栈（如果有）
            if (event.getThrowableProxy() != null) {
                detail.append("\n");
                appendThrowable(detail, event.getThrowableProxy());
            }

            // 截断过长的详情（平台端数据库字段有限）
            String detailStr = detail.length() > 4000
                    ? detail.substring(0, 4000) + "...[truncated]"
                    : detail.toString();

            // 转换时间
            LocalDateTime logTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getTimeStamp()),
                    ZoneId.systemDefault());

            // 放入队列（非阻塞，队列满时丢弃）
            LogEntry entry = new LogEntry(toLevelString(event.getLevel()), detailStr, logTime);
            queue.offer(entry);
        }

        private void appendThrowable(StringBuilder sb, ch.qos.logback.classic.spi.IThrowableProxy proxy) {
            sb.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append("\n");
            ch.qos.logback.classic.spi.StackTraceElementProxy[] steArr = proxy.getStackTraceElementProxyArray();
            if (steArr != null) {
                int maxLines = Math.min(steArr.length, 10);
                for (int i = 0; i < maxLines; i++) {
                    sb.append("\tat ").append(steArr[i].getSTEAsString()).append("\n");
                }
                if (steArr.length > maxLines) {
                    sb.append("\t... ").append(steArr.length - maxLines).append(" more\n");
                }
            }
        }
    }
}
