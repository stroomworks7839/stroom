package stroom.proxy.app.handler;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.app.DataDirProvider;
import stroom.proxy.repo.ProxyServices;
import stroom.util.concurrent.ThreadUtil;
import stroom.util.io.FileUtil;
import stroom.util.io.SimplePathCreator;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.time.StroomDuration;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ForwardHttpPostDestination implements ForwardDestination {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ForwardHttpPostDestination.class);

    private static final String ERROR_LOG = "error.log";

    private final StreamDestination destination;
    private final DirQueue forwardQueue;
    private final DirQueue retryQueue;
    private final CleanupDirQueue cleanupDirQueue;
    private final ForwardHttpPostConfig forwardHttpPostConfig;
    //    private final StroomDuration retryDelay;
//    private final Integer maxRetries;
    private final String destinationName;
    private final ForwardFileDestination failureDestination;

    public ForwardHttpPostDestination(final String destinationName,
                                      final StreamDestination destination,
                                      final CleanupDirQueue cleanupDirQueue,
                                      final ForwardHttpPostConfig forwardHttpPostConfig,
                                      final ProxyServices proxyServices,
                                      final DirQueueFactory dirQueueFactory,
                                      final ThreadConfig threadConfig,
                                      final DataDirProvider dataDirProvider,
                                      final SimplePathCreator simplePathCreator) {
        this.destination = destination;
        this.cleanupDirQueue = cleanupDirQueue;
        this.destinationName = destinationName;
        this.forwardHttpPostConfig = forwardHttpPostConfig;

        // TODO Add a health URL to check the forward dest every minute or so
        //  If it returns false we stop consuming from the forwardQueue/retryQueue until
        //  it returns true.

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
        final DirQueueTransfer forwarding = new DirQueueTransfer(forwardQueue::next, this::forwardDir);
        final DirQueueTransfer retrying = new DirQueueTransfer(retryQueue::next, this::retryDir);
        proxyServices.addParallelExecutor(
                "forward - " + destinationName,
                () -> forwarding,
                threadConfig.getForwardThreadCount());
        proxyServices.addParallelExecutor(
                "retry - " + destinationName,
                () -> retrying,
                threadConfig.getForwardRetryThreadCount());

        // Create failure destination.
        failureDestination = setupFailureDestination(
                forwardHttpPostConfig, simplePathCreator, forwardingDir);
    }

    private ForwardFileDestination setupFailureDestination(final ForwardHttpPostConfig forwardHttpPostConfig,
                                                           final SimplePathCreator simplePathCreator,
                                                           final Path forwardingDir) {
        final ForwardFileDestination failureDestination;
        final Path failureDir = forwardingDir.resolve("03_failure");
        final String errorSubPathTemplate = forwardHttpPostConfig.getErrorSubPathTemplate();
        DirUtil.ensureDirExists(failureDir);
        failureDestination = new ForwardFileDestinationImpl(
                failureDir,
                getName() + " (failures)",
                errorSubPathTemplate,
                ForwardFileConfig.DEFAULT_TEMPLATING_MODE,
                simplePathCreator);
        return failureDestination;
    }

    @Override
    public void add(final Path sourceDir) {
        forwardQueue.add(sourceDir);
    }

    @Override
    public String getName() {
        return forwardHttpPostConfig.getName();
    }

    @Override
    public String getDestinationDescription() {
        return forwardHttpPostConfig.getForwardUrl()
               + " (instant=" + forwardHttpPostConfig.isInstant() + ")";
    }

    @Override
    public String toString() {
        return asString();
    }

    private boolean forwardDir(final Path dir) {
        LOGGER.debug("ForwardDir() - destinationName: {}, dir: {}", destinationName, dir);
        try {
            try {
                final FileGroup fileGroup = new FileGroup(dir);
                final AttributeMap attributeMap = new AttributeMap();
                AttributeMapUtil.read(fileGroup.getMeta(), attributeMap);
                // Make sure we tell the destination we are sending zip data.
                attributeMap.put(StandardHeaderArguments.COMPRESSION, StandardHeaderArguments.COMPRESSION_ZIP);

                // Send the data.
                try (final InputStream inputStream =
                        new BufferedInputStream(Files.newInputStream(fileGroup.getZip()))) {
                    destination.send(attributeMap, inputStream);
                }

                // We have completed sending so can delete the data.
                cleanupDirQueue.add(dir);

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
                final int maxRetries = forwardHttpPostConfig.getMaxRetries();
                if (canRetry && !isInfiniteRetries(maxRetries)) {
                    final int errorCount = countErrors(dir);
                    LOGGER.debug("'{}' - maxRetries: {}, errorCount: {}",
                            destinationName, maxRetries, errorCount);
                    canRetry = errorCount < maxRetries;
                }
                if (canRetry) {
                    LOGGER.debug("Retrying {}", dir);
                    // TODO write a state file that holds the failureTime and the attemptsCount

                    // TODO Make a LoopingDirQueue that can
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

    private boolean isInfiniteRetries(final int maxRetries) {
        return maxRetries == ForwardHttpPostConfig.INFINITE_RETRIES_VALUE;
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
            final Path errorPath = dir.resolve(ERROR_LOG);
            final String line = sb.toString();
            Files.writeString(errorPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e2) {
            LOGGER.error(e2::getMessage, e2);
        }
    }

    private int countErrors(final Path dir) {
        int count = 0;
        try {
            final Path errorPath = dir.resolve(ERROR_LOG);
            if (Files.isRegularFile(errorPath)) {
                try (final BufferedReader bufferedReader = Files.newBufferedReader(errorPath)) {
                    String line = bufferedReader.readLine();
                    while (line != null) {
                        count++;
                        line = bufferedReader.readLine();
                    }
                }
            }
        } catch (final IOException e) {
            LOGGER.error(e::getMessage, e);
        }
        return count;
    }

    private void retryDir(final Path dir) {
        if (!forwardDir(dir)) {
            // If we failed to send then wait for a bit.
            final StroomDuration retryDelay = forwardHttpPostConfig.getRetryDelay();
            if (!retryDelay.isZero()) {
                LOGGER.trace("'{}' - adding delay {}", destinationName, retryDelay);
                ThreadUtil.sleep(retryDelay);
            }
        }
    }
}
