package stroom.proxy.app.handler;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.repo.ProxyServices;
import stroom.proxy.repo.RepoDirProvider;
import stroom.util.concurrent.ThreadUtil;
import stroom.util.io.FileUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.time.StroomDuration;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ForwardHttpPostDestination {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ForwardHttpPostDestination.class);

    private static final String ERROR_LOG = "error.log";

    private final StreamDestination destination;
    private final DirQueue forwardQueue;
    private final DirQueue retryQueue;
    private final CleanupDirQueue cleanupDirQueue;
    private final StroomDuration retryDelay;
    private final int maxRetries;
    private final String destinationName;
    private final ForwardFileDestination failureDestination;

    public ForwardHttpPostDestination(final String destinationName,
                                      final StreamDestination destination,
                                      final CleanupDirQueue cleanupDirQueue,
                                      final StroomDuration retryDelay,
                                      final int maxRetries,
                                      final ProxyServices proxyServices,
                                      final DirQueueFactory sequentialDirQueueFactory,
                                      final int forwardThreads,
                                      final int retryThreads,
                                      final RepoDirProvider repoDirProvider) {
        this.destination = destination;
        this.cleanupDirQueue = cleanupDirQueue;
        this.destinationName = destinationName;
        this.retryDelay = retryDelay;
        this.maxRetries = maxRetries;

        forwardQueue = sequentialDirQueueFactory.create(
                "20_forward_" + destinationName,
                20,
                "forward - " + destinationName);
        retryQueue = sequentialDirQueueFactory.create(
                "21_retry_" + destinationName,
                21,
                "retry - " + destinationName);
        final DirQueueTransfer forwarding = new DirQueueTransfer(forwardQueue::next, this::forwardDir);
        final DirQueueTransfer retrying = new DirQueueTransfer(retryQueue::next, this::retryDir);
        proxyServices.addParallelExecutor(
                "forward - " + destinationName,
                () -> forwarding,
                forwardThreads);
        proxyServices.addParallelExecutor(
                "retry - " + destinationName,
                () -> retrying,
                retryThreads);

        // Create failure destination.
        String failureDirName = "22_failure_" + destinationName;
        failureDirName = failureDirName.replaceAll("[^a-zA-Z0-9-_]", "_");
        final Path failureDir = repoDirProvider.get().resolve(failureDirName);
        DirUtil.ensureDirExists(failureDir);
        failureDestination = new ForwardFileDestinationImpl(failureDir);
    }

    public void add(final Path sourceDir) {
        forwardQueue.add(sourceDir);
    }

    private boolean forwardDir(final Path dir) {
        try {
            final FileGroup fileGroup = new FileGroup(dir);
            final AttributeMap attributeMap = new AttributeMap();
            AttributeMapUtil.read(fileGroup.getMeta(), attributeMap);
            // Make sure we tell the destination we are sending zip data.
            attributeMap.put(StandardHeaderArguments.COMPRESSION, StandardHeaderArguments.COMPRESSION_ZIP);

            // Send the data.
            try (final InputStream inputStream = new BufferedInputStream(Files.newInputStream(fileGroup.getZip()))) {
                destination.send(attributeMap, inputStream);
            }

            // We have completed sending so can delete the data.
            cleanupDirQueue.add(dir);

            // Return true for success.
            return true;

        } catch (final IOException e) {
            LOGGER.error(
                    () -> "Error sending '" + FileUtil.getCanonicalPath(dir) + "' to '" + destinationName + "'.", e);
            LOGGER.debug(e::getMessage, e);

            // Add to the errors
            addError(dir, e);

            // Count errors.
            final int errorCount = countErrors(dir);
            if (errorCount >= maxRetries) {
                // If we exceeded the max number of retries then move the data to the failure destination.
                failureDestination.add(dir);
            } else {
                // Add the dir to the retry queue ready to be tried again.
                retryQueue.add(dir);
            }
        }

        // Failed, return false.
        return false;
    }

    private void addError(final Path dir, final Exception e) {
        try {
            final StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
            if (e.getMessage() != null) {
                sb.append(" ");
                sb.append(e.getMessage().replaceAll("\n", " "));
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
            if (!retryDelay.isZero()) {
                LOGGER.trace("'{}' - adding delay {}", destinationName, retryDelay);
                ThreadUtil.sleep(retryDelay);
            }
        }
    }
}
