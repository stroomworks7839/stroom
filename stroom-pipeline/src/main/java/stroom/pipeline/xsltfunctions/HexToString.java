/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pipeline.xsltfunctions;

import stroom.util.shared.StringUtil;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.om.EmptyAtomicSequence;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.StringValue;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

class HexToString extends StroomExtensionFunctionCall {
    @Override
    protected Sequence call(String functionName, XPathContext context, Sequence[] arguments) {
        String result = null;

        try {
            String hex = getSafeString(functionName, context, arguments, 0);
            final String charsetName = getSafeString(functionName, context, arguments, 1);

            // Strip any whitespace characters
            if (hex != null) {
                hex = hex.replaceAll("\\s*", "");
            }
            if (!StringUtil.isBlank(hex)) {
                final Charset charset = Charset.forName(charsetName);
                final ByteBuffer bytes = decodeHex(hex);
                result = charset.decode(bytes).toString();
            } else {
                result = "";
            }
        } catch (final XPathException | RuntimeException e) {
            final StringBuilder sb = new StringBuilder();
            sb.append(e.getMessage());
            outputWarning(context, sb, e);
        }

        if (result == null) {
            return EmptyAtomicSequence.getInstance();
        }
        return StringValue.makeStringValue(result);
    }

    private ByteBuffer decodeHex(final String hex) {
        final int length = hex.length();
        if (length % 2 > 0) {
            throw new IllegalArgumentException("Invalid string length: " + length);
        }

        final ByteBuffer bytes = ByteBuffer.allocate(length / 2);
        for (int i = 0; i < length; i += 2) {
            bytes.put((byte)Integer.parseInt(hex.substring(i, i + 2), 16));
        }

        return bytes.rewind();
    }
}
