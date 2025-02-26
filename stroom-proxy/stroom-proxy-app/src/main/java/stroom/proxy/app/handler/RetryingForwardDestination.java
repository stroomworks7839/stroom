package stroom.proxy.app.handler;

import stroom.proxy.app.DataDirProvider;
import stroom.proxy.repo.ProxyServices;
import stroom.util.NullSafe;
import stroom.util.concurrent.ThreadUtil;
import stroom.util.io.FileUtil;
import stroom.util.io.PathCreator;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.time.StroomDuration;
import stroom.util.time.TimeUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Wraps another {@link ForwardDestination} with a forwarding queue and retry logic.
 * Directories passed to {@link RetryingForwardDestination#add(Path)} will simply be placed on the
 * forward queue. A pool of threads will consume from this queue, isolating the caller from any failures
 * when calling {@link ForwardDestination#add(Path)} on the delegate. Failures will result in the directory
 * being placed on the retry queue, depending on the {@link ForwardQueueConfig}.
 */
public class RetryingForwardDestination implements ForwardDestination {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(RetryingForwardDestination.class);

    private static final String ERROR_LOG_FILENAME = "error.log";
    private static final String RETRY_STATE_FILENAME = "retry.state";
    private static final int ONE_SECOND = 1_000;

    private final ForwardQueueConfig forwardQueueConfig;
    private final ForwardDestination delegateDestination;
    private final CleanupDirQueue cleanupDirQueue;
    private final ProxyServices proxyServices;

    private final DirQueue forwardQueue;
    private final DirQueue retryQueue;
    private final ForwardFileDestination failureDestination;
    private final String destinationName;

    public RetryingForwardDestination(final ForwardQueueConfig forwardQueueConfig,
                                      final ForwardDestination delegateDestination,
                                      final DataDirProvider dataDirProvider,
                                      final PathCreator pathCreator,
                                      final CleanupDirQueue cleanupDirQueue,
                                      final DirQueueFactory dirQueueFactory,
                                      final ProxyServices proxyServices) {

        this.forwardQueueConfig = Objects.requireNonNull(forwardQueueConfig);
        this.delegateDestination = Objects.requireNonNull(delegateDestination);
        this.cleanupDirQueue = Objects.requireNonNull(cleanupDirQueue);
        this.proxyServices = Objects.requireNonNull(proxyServices);

        this.destinationName = delegateDestination.getName();
        final String safeDirName = DirUtil.makeSafeName(destinationName);
        final Path forwardingDir = dataDirProvider.get()
                .resolve(DirNames.FORWARDING).resolve(safeDirName);
        DirUtil.ensureDirExists(forwardingDir);

        forwardQueue = dirQueueFactory.create(
                forwardingDir.resolve("01_forward"),
                50,
                "forward - " + destinationName);
        retryQueue = dirQueueFactory.create(
                forwardingDir.resolve("02_retry"),
                51,
                "retry - " + destinationName);

        final DirQueueTransfer forwarding = new DirQueueTransfer(
                forwardQueue::next, delegateDestination::add);
        final DirQueueTransfer retrying = new DirQueueTransfer(
                retryQueue::next, this::retryDir);
        proxyServices.addParallelExecutor(
                "forward - " + destinationName,
                () -> forwarding,
                forwardQueueConfig.getForwardThreadCount());
        proxyServices.addParallelExecutor(
                "retry - " + destinationName,
                () -> retrying,
                forwardQueueConfig.getForwardRetryThreadCount());

        // Create failure destination.
        failureDestination = setupFailureDestination(
                forwardQueueConfig, pathCreator, forwardingDir);
    }

    @Override
    public void add(final Path sourceDir) {
        LOGGER.debug("'{}' - add(), dir: {}", destinationName, sourceDir);
        // Add to the forward queue to isolate the caller from failures in the delegate.
        // This will move sourceDir into the queue dir
        forwardQueue.add(sourceDir);
    }

    @Override
    public String getName() {
        return delegateDestination.getName();
    }

    @Override
    public String getDestinationDescription() {
        return delegateDestination.getDestinationDescription();
    }

    @Override
    public String toString() {
        return asString();
    }

    /**
     * For testing
     */
    Path getFailureDir() {
        return failureDestination.getStoreDir();
    }

    private ForwardFileDestination setupFailureDestination(final ForwardQueueConfig forwardQueueConfig,
                                                           final PathCreator simplePathCreator,
                                                           final Path forwardingDir) {
        final ForwardFileDestination failureDestination;
        final Path failureDir = forwardingDir.resolve("03_failure");
        final String errorSubPathTemplate = forwardQueueConfig.getErrorSubPathTemplate();
        DirUtil.ensureDirExists(failureDir);
        failureDestination = new ForwardFileDestinationImpl(
                failureDir,
                getName() + " (failures)",
                errorSubPathTemplate,
                ForwardFileConfig.DEFAULT_TEMPLATING_MODE,
                simplePathCreator);
        return failureDestination;
    }

    private boolean forwardDir(final Path dir) {
        LOGGER.debug("'{}' - forwardDir(), dir: {}", destinationName, dir);
        try {
            try {
                delegateDestination.add(dir);
//                // We have completed sending so can delete the data.
//                cleanupDirQueue.add(dir);
                // Return true for success.
                return true;
            } catch (final Exception e) {
                LOGGER.error(() ->
                        "Error sending '" + FileUtil.getCanonicalPath(dir)
                        + "' to '" + destinationName + "': "
                        + LogUtil.exceptionMessage(getCause(e)) + ". " +
                        "(Enable DEBUG for stack trace.)");
                LOGGER.debug(e::getMessage, e);

                // Add to the errors
                addError(dir, e);

                // Have to assume we can retry
                boolean canRetry = true;
                if (e instanceof ForwardException forwardException) {
                    canRetry = forwardException.isRecoverable();
                }

                // Count errors.
                if (canRetry) {
                    final StroomDuration maxRetryAge = forwardQueueConfig.getMaxRetryAge();
                    if (maxRetryAge.isZero()) {
                        // No point creating the state file
                        canRetry = false;
                    } else {
                        // Read the state file
                        // If it exists get previous state and update it for next time
                        // If not exists, create it
                        final RetryState retryState = getAndUpdateRetryState(dir);
                        if (retryState != null) {
                            final Duration timeSinceFirstAttempt = retryState.getTimeSinceFirstAttempt();
                            LOGGER.debug("'{}' - maxRetries: {}, retryState: {}, timeSinceFirstAttempt: {}",
                                    destinationName, maxRetryAge, retryState, timeSinceFirstAttempt);
                            canRetry = TimeUtils.isLessThan(
                                    retryState.getTimeSinceFirstAttempt(),
                                    maxRetryAge.getDuration());
                        }
                    }
                }
                if (canRetry) {
                    LOGGER.debug("Retrying {}", dir);
                    // TODO Make a LoopingDirQueue that can go back to the head of the queue
                    addToRetryQueue(dir);
                } else {
                    LOGGER.debug("Adding {} to failure queue", dir);
                    // If we exceeded the max number of retries then move the data to the failure destination.
                    failureDestination.add(dir);
                }
            }
        } catch (final Throwable t) {
            LOGGER.error(t::getMessage, t);
        }

        // Failed, return false.
        return false;
    }

    private void addToRetryQueue(final Path dir) {
        // Add the dir to the retry queue ready to be tried again.
        retryQueue.add(dir);
    }

    private void retryDir(final Path dir) {
        LOGGER.debug("'{}' - retryDir(), dir: {}", destinationName, dir);

        delayRetry(dir);

        if (!proxyServices.isShuttingDown()) {
            forwardDir(dir);
        } else {
            // No point trying to forward now
            throw new RuntimeException("Proxy is shutting down");
        }
    }

    private void delayRetry(final Path dir) {
        final RetryState retryState = getRetryState(dir);
        final long lastAttemptEpochMs = NullSafe.getOrElse(
                retryState,
                RetryState::lastAttemptEpochMs,
                0L);
        final int attempts = NullSafe.getOrElse(retryState, RetryState::attempts, -1);
        final StroomDuration retryDelay = forwardQueueConfig.getRetryDelay();
        final double retryDelayGrowthFactor = forwardQueueConfig.getRetryDelayGrowthFactor();
        final long retryDelayMs;
        if (retryDelayGrowthFactor > 1 && retryState != null) {
            retryDelayMs = Math.min(
                    (long) (retryDelay.toMillis() * Math.pow(retryDelayGrowthFactor, attempts)),
                    forwardQueueConfig.getMaxRetryDelay().toMillis());
        } else {
            retryDelayMs = retryDelay.toMillis();
        }

        final long notBeforeEpochMs = lastAttemptEpochMs + retryDelayMs;
        long delay = notBeforeEpochMs - System.currentTimeMillis();

        LOGGER.debug(() -> LogUtil.message("'{}' - notBefore {}, retryDelayMs {}, attempts: {}",
                destinationName,
                Instant.ofEpochMilli(notBeforeEpochMs),
                Duration.ofMillis(retryDelayMs),
                attempts));

        while (delay > 0 && !proxyServices.isShuttingDown()) {
            final long sleepMs = Math.min(ONE_SECOND, delay);
            LOGGER.debug("Sleeping for {}ms", sleepMs);
            ThreadUtil.sleep(sleepMs);
            if (sleepMs == ONE_SECOND) {
                delay = notBeforeEpochMs - System.currentTimeMillis();
            } else {
                break;
            }
        }
    }

    private Throwable getCause(final Throwable e) {
        return e instanceof ForwardException forwardException
               && forwardException.getCause() != null
                ? forwardException.getCause()
                : e;
    }

    private void addError(final Path dir, final Exception e) {
        try {
            final StringBuilder sb = new StringBuilder(getCause(e).getClass().getSimpleName());
            if (e.getMessage() != null) {
                sb.append(" ");
                sb.append(e.getMessage().replace('\n', ' '));
            }
            sb.append("\n");
            final Path errorPath = getErrorLogFile(dir);
            final String line = sb.toString();
            Files.writeString(errorPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e2) {
            LOGGER.error(e2::getMessage, e2);
        }
    }

    private RetryState getRetryState(final Path dir) {
        final Path retryStateFile = getRetryStateFile(dir);
        if (Files.isRegularFile(retryStateFile)) {
            try {
                final byte[] bytes = Files.readAllBytes(retryStateFile);
                return RetryState.deserialise(bytes);
            } catch (IOException e) {
                LOGGER.error(() ->
                        LogUtil.message("Error reading retry file {}: {}", LogUtil.exceptionMessage(e), e));
                return null;
            }
        } else {
            if (Files.exists(retryStateFile)) {
                LOGGER.error("{} exists but is not a file, ignoring", retryStateFile);
            } else {
                LOGGER.error("{} does not exist, ignoring", retryStateFile);
            }
            return null;
        }
    }

    private RetryState getAndUpdateRetryState(final Path dir) {
        final Path retryStateFile = getRetryStateFile(dir);
        RetryState retryState = null;
        if (Files.isRegularFile(retryStateFile)) {
            try (RandomAccessFile reader = new RandomAccessFile(retryStateFile.toFile(), "rwd");
                    FileChannel channel = reader.getChannel()) {
                final ByteBuffer byteBuffer = ByteBuffer.allocate(RetryState.TOTAL_BYTES);
                // First read the existing value
                channel.read(byteBuffer);
                byteBuffer.flip();
                retryState = RetryState.deserialise(byteBuffer);
                final RetryState newRetryState = retryState.cloneAndUpdate();
                LOGGER.debug("'{}' - retryStateFile: {}, retryState: {}, newRetryState: {}",
                        destinationName, retryStateFile, retryState, newRetryState);
                retryState = newRetryState;

                // Get ready for writing back to the file
                channel.position(0);
                byteBuffer.flip();
                retryState.serialise(byteBuffer);
                byteBuffer.flip();
                final int writeCount = channel.write(byteBuffer);
                if (writeCount != RetryState.TOTAL_BYTES) {
                    throw new IllegalStateException(LogUtil.message("Unexpected writeCount {}, expecting {}",
                            writeCount, RetryState.TOTAL_BYTES));
                }
            } catch (IOException e) {
                LOGGER.error(() ->
                        LogUtil.message("Error updating retry file {}: {}", e.getMessage(), e));
            }
        } else {
            if (Files.exists(retryStateFile)) {
                LOGGER.error(() -> LogUtil.message("{} exists but is not a file, ignoring", retryStateFile));
            } else {
                // Not exists so write a new one
                retryState = RetryState.initial();
                try {
                    Files.write(retryStateFile,
                            retryState.serialise(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                } catch (IOException e) {
                    LOGGER.error(e::getMessage, e);
                }
            }
        }
        return retryState;
    }

    private Path getRetryStateFile(final Path dir) {
        return Objects.requireNonNull(dir).resolve(RETRY_STATE_FILENAME);
    }

    private Path getErrorLogFile(final Path dir) {
        return Objects.requireNonNull(dir).resolve(ERROR_LOG_FILENAME);
    }
}
