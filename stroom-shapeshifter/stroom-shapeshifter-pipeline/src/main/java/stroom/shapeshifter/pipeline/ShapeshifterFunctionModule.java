/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.pipeline.function.CidrToNumericIpRangeFunction;
import stroom.shapeshifter.pipeline.function.CosineSimilarityFunction;
import stroom.shapeshifter.pipeline.function.CurrentTimeFunction;
import stroom.shapeshifter.pipeline.function.CurrentUnixTimeFunction;
import stroom.shapeshifter.pipeline.function.DecodeUrlFunction;
import stroom.shapeshifter.pipeline.function.EncodeUrlFunction;
import stroom.shapeshifter.pipeline.function.FormatDateFunction;
import stroom.shapeshifter.pipeline.function.FormatDateTimeFunction;
import stroom.shapeshifter.pipeline.function.FromUnixTimeFunction;
import stroom.shapeshifter.pipeline.function.HashFunction;
import stroom.shapeshifter.pipeline.function.HexToDecFunction;
import stroom.shapeshifter.pipeline.function.HexToOctFunction;
import stroom.shapeshifter.pipeline.function.HexToStringFunction;
import stroom.shapeshifter.pipeline.function.HostAddressFunction;
import stroom.shapeshifter.pipeline.function.HostNameFunction;
import stroom.shapeshifter.pipeline.function.IpInCidrFunction;
import stroom.shapeshifter.pipeline.function.JsonToXmlFunction;
import stroom.shapeshifter.pipeline.function.NumericIpFunction;
import stroom.shapeshifter.pipeline.function.ParseDateTimeFunction;
import stroom.shapeshifter.pipeline.function.ParseUriFunction;
import stroom.shapeshifter.pipeline.function.PointIsInsideXyPolygonFunction;
import stroom.shapeshifter.pipeline.function.RandomFunction;
import stroom.shapeshifter.pipeline.function.ToUnixTimeFunction;

import java.util.List;

/**
 * Stroom's XSLT functions as Shapeshifter functions, under Stroom's names (design 26 §5).
 * Group A — the pure ones and the clock — here; the pipeline's context (group B) and the
 * lookups and the network (group C) follow in their phases.
 */
public class ShapeshifterFunctionModule extends AbstractShapeshifterFunctionModule {

    /** Group A as instances, for a registry built without Guice — a test, a command line. */
    public static List<FunctionDefinition> groupA() {
        return List.of(
                new CidrToNumericIpRangeFunction(),
                new CosineSimilarityFunction(),
                new CurrentTimeFunction(),
                new CurrentUnixTimeFunction(),
                new DecodeUrlFunction(),
                new EncodeUrlFunction(),
                new FormatDateFunction(),
                new FormatDateTimeFunction(),
                new FromUnixTimeFunction(),
                new HashFunction(),
                new HexToDecFunction(),
                new HexToOctFunction(),
                new HexToStringFunction(),
                new HostAddressFunction(),
                new HostNameFunction(),
                new IpInCidrFunction(),
                new JsonToXmlFunction(),
                new NumericIpFunction(),
                new ParseDateTimeFunction(),
                new ParseUriFunction(),
                new PointIsInsideXyPolygonFunction(),
                new RandomFunction(),
                new ToUnixTimeFunction());
    }

    @Override
    protected void configureFunctions() {
        bindFunction(CidrToNumericIpRangeFunction.class);
        bindFunction(CosineSimilarityFunction.class);
        bindFunction(CurrentTimeFunction.class);
        bindFunction(CurrentUnixTimeFunction.class);
        bindFunction(DecodeUrlFunction.class);
        bindFunction(EncodeUrlFunction.class);
        bindFunction(FormatDateFunction.class);
        bindFunction(FormatDateTimeFunction.class);
        bindFunction(FromUnixTimeFunction.class);
        bindFunction(HashFunction.class);
        bindFunction(HexToDecFunction.class);
        bindFunction(HexToOctFunction.class);
        bindFunction(HexToStringFunction.class);
        bindFunction(HostAddressFunction.class);
        bindFunction(HostNameFunction.class);
        bindFunction(IpInCidrFunction.class);
        bindFunction(JsonToXmlFunction.class);
        bindFunction(NumericIpFunction.class);
        bindFunction(ParseDateTimeFunction.class);
        bindFunction(ParseUriFunction.class);
        bindFunction(PointIsInsideXyPolygonFunction.class);
        bindFunction(RandomFunction.class);
        bindFunction(ToUnixTimeFunction.class);
    }
}
