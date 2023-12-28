package stroom.proxy.app.handler;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.StroomStatusCode;
import stroom.receive.common.RequestHandler;
import stroom.receive.common.StroomStreamException;
import stroom.util.cert.CertificateExtractor;
import stroom.util.io.StreamUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.UUID;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Main entry point to handling proxy requests.
 * <p>
 * This class used the main context and forwards the request on to our
 * dynamic mini proxy.
 */
public class ProxyRequestHandler implements RequestHandler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ProxyRequestHandler.class);
    private static final String ZERO_CONTENT = "0";

    private final ProxyId proxyId;
    private final CertificateExtractor certificateExtractor;
    private final ReceiverFactory receiverFactory;

    @Inject
    public ProxyRequestHandler(final ProxyId proxyId,
                               final CertificateExtractor certificateExtractor,
                               final ReceiverFactory receiverFactory) {
        this.proxyId = proxyId;
        this.certificateExtractor = certificateExtractor;
        this.receiverFactory = receiverFactory;
    }

    @Override
    public void handle(final HttpServletRequest request, final HttpServletResponse response) {
        try {
            final Instant startTime = Instant.now();

            // Create attribute map from headers.
            final AttributeMap attributeMap = AttributeMapUtil.create(request, certificateExtractor);

            // Create a new proxy id for the request so we can track progress and report back the UUID to the sender,
            final String requestUuid = UUID.randomUUID().toString();
            final String proxyIdString = proxyId.getId();
            final String result = proxyIdString + ": " + requestUuid;

            LOGGER.debug(() -> "Adding proxy id attribute: " + proxyIdString + ": " + requestUuid);
            attributeMap.put(proxyIdString, requestUuid);

            // Treat differently depending on compression type.
            String compression = attributeMap.get(StandardHeaderArguments.COMPRESSION);
            if (compression != null && !compression.isEmpty()) {
                compression = compression.toUpperCase(StreamUtil.DEFAULT_LOCALE);
                if (!StandardHeaderArguments.VALID_COMPRESSION_SET.contains(compression)) {
                    throw new StroomStreamException(
                            StroomStatusCode.UNKNOWN_COMPRESSION, attributeMap, compression);
                }
            }

            // TODO : If storing is not configured and we are forwarding to single destination then just check the feed
            //  if feed checking is configured and stream the data straight out to the destination.

            if (ZERO_CONTENT.equals(attributeMap.get(StandardHeaderArguments.CONTENT_LENGTH))) {
                LOGGER.warn("process() - Skipping Zero Content " + attributeMap);

            } else {
                final Receiver receiver = receiverFactory.get(attributeMap);
                receiver.receive(startTime, attributeMap, request.getRequestURI(), request::getInputStream);
            }

            response.setStatus(HttpStatus.SC_OK);

            LOGGER.debug(() -> "Writing proxy id attribute to response: " + result);
            try (final PrintWriter writer = response.getWriter()) {
                writer.println(result);
            } catch (final IOException e) {
                LOGGER.error(e.getMessage(), e);
            }

        } catch (final StroomStreamException e) {
            e.sendErrorResponse(response);
        }
    }
}
