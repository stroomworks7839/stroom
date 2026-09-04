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
import stroom.shapeshifter.pipeline.function.AddMetaFunction;
import stroom.shapeshifter.pipeline.function.CidrToNumericIpRangeFunction;
import stroom.shapeshifter.pipeline.function.ClassificationFunction;
import stroom.shapeshifter.pipeline.function.ColFromFunction;
import stroom.shapeshifter.pipeline.function.ColToFunction;
import stroom.shapeshifter.pipeline.function.CosineSimilarityFunction;
import stroom.shapeshifter.pipeline.function.CurrentTimeFunction;
import stroom.shapeshifter.pipeline.function.CurrentUnixTimeFunction;
import stroom.shapeshifter.pipeline.function.CurrentUserFunction;
import stroom.shapeshifter.pipeline.function.DecodeUrlFunction;
import stroom.shapeshifter.pipeline.function.DictionaryFunction;
import stroom.shapeshifter.pipeline.function.EncodeUrlFunction;
import stroom.shapeshifter.pipeline.function.FeedAttributeFunction;
import stroom.shapeshifter.pipeline.function.FeedNameFunction;
import stroom.shapeshifter.pipeline.function.FormatDateFunction;
import stroom.shapeshifter.pipeline.function.FormatDateTimeFunction;
import stroom.shapeshifter.pipeline.function.FromUnixTimeFunction;
import stroom.shapeshifter.pipeline.function.GetFunction;
import stroom.shapeshifter.pipeline.function.HashFunction;
import stroom.shapeshifter.pipeline.function.HexToDecFunction;
import stroom.shapeshifter.pipeline.function.HexToOctFunction;
import stroom.shapeshifter.pipeline.function.HexToStringFunction;
import stroom.shapeshifter.pipeline.function.HostAddressFunction;
import stroom.shapeshifter.pipeline.function.HostNameFunction;
import stroom.shapeshifter.pipeline.function.IpInCidrFunction;
import stroom.shapeshifter.pipeline.function.JsonToXmlFunction;
import stroom.shapeshifter.pipeline.function.LineFromFunction;
import stroom.shapeshifter.pipeline.function.LineToFunction;
import stroom.shapeshifter.pipeline.function.LinkFunction;
import stroom.shapeshifter.pipeline.function.LogFunction;
import stroom.shapeshifter.pipeline.function.ManifestForIdFunction;
import stroom.shapeshifter.pipeline.function.ManifestNoArgsFunction;
import stroom.shapeshifter.pipeline.function.MetaAttributeFunction;
import stroom.shapeshifter.pipeline.function.MetaFunction;
import stroom.shapeshifter.pipeline.function.MetaKeysFunction;
import stroom.shapeshifter.pipeline.function.MetaStreamForIdFunction;
import stroom.shapeshifter.pipeline.function.MetaStreamNoArgsFunction;
import stroom.shapeshifter.pipeline.function.NumericIpFunction;
import stroom.shapeshifter.pipeline.function.ParentForIdFunction;
import stroom.shapeshifter.pipeline.function.ParentIdFunction;
import stroom.shapeshifter.pipeline.function.ParseDateTimeFunction;
import stroom.shapeshifter.pipeline.function.ParseUriFunction;
import stroom.shapeshifter.pipeline.function.PartNoFunction;
import stroom.shapeshifter.pipeline.function.PipelineNameFunction;
import stroom.shapeshifter.pipeline.function.PointIsInsideXyPolygonFunction;
import stroom.shapeshifter.pipeline.function.PutFunction;
import stroom.shapeshifter.pipeline.function.RandomFunction;
import stroom.shapeshifter.pipeline.function.RecordNoFunction;
import stroom.shapeshifter.pipeline.function.SearchIdFunction;
import stroom.shapeshifter.pipeline.function.SourceFunction;
import stroom.shapeshifter.pipeline.function.SourceIdFunction;
import stroom.shapeshifter.pipeline.function.StreamIdNamedFunction;
import stroom.shapeshifter.pipeline.function.ToUnixTimeFunction;

import java.util.List;

/**
 * Stroom's XSLT functions as Shapeshifter functions, under Stroom's names (design 26 §5).
 * Group A, the pure ones and the clock, and group B, the pipeline's context, are here; the
 * lookups and the network (group C) follow in phase 4.
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
        // Group B (phase 3): the pipeline's context.
        bindFunction(AddMetaFunction.class);
        bindFunction(ClassificationFunction.class);
        bindFunction(ColFromFunction.class);
        bindFunction(ColToFunction.class);
        bindFunction(CurrentUserFunction.class);
        bindFunction(DictionaryFunction.class);
        bindFunction(FeedAttributeFunction.class);
        bindFunction(FeedNameFunction.class);
        bindFunction(GetFunction.class);
        bindFunction(LineFromFunction.class);
        bindFunction(LineToFunction.class);
        bindFunction(LinkFunction.class);
        bindFunction(LogFunction.class);
        bindFunction(ManifestForIdFunction.class);
        bindFunction(ManifestNoArgsFunction.class);
        bindFunction(MetaAttributeFunction.class);
        bindFunction(MetaFunction.class);
        bindFunction(MetaKeysFunction.class);
        bindFunction(MetaStreamForIdFunction.class);
        bindFunction(MetaStreamNoArgsFunction.class);
        bindFunction(ParentForIdFunction.class);
        bindFunction(ParentIdFunction.class);
        bindFunction(PartNoFunction.class);
        bindFunction(PipelineNameFunction.class);
        bindFunction(PutFunction.class);
        bindFunction(RecordNoFunction.class);
        bindFunction(SearchIdFunction.class);
        bindFunction(SourceFunction.class);
        bindFunction(SourceIdFunction.class);
        bindFunction(StreamIdNamedFunction.class);
    }

    /** Group B as instances: the pipeline's context. */
    public static List<FunctionDefinition> groupB() {
        return List.of(
                new AddMetaFunction(),
                new ClassificationFunction(),
                new ColFromFunction(),
                new ColToFunction(),
                new CurrentUserFunction(),
                new DictionaryFunction(),
                new FeedAttributeFunction(),
                new FeedNameFunction(),
                new GetFunction(),
                new LineFromFunction(),
                new LineToFunction(),
                new LinkFunction(),
                new LogFunction(),
                new ManifestForIdFunction(),
                new ManifestNoArgsFunction(),
                new MetaAttributeFunction(),
                new MetaFunction(),
                new MetaKeysFunction(),
                new MetaStreamForIdFunction(),
                new MetaStreamNoArgsFunction(),
                new ParentForIdFunction(),
                new ParentIdFunction(),
                new PartNoFunction(),
                new PipelineNameFunction(),
                new PutFunction(),
                new RecordNoFunction(),
                new SearchIdFunction(),
                new SourceFunction(),
                new SourceIdFunction(),
                new StreamIdNamedFunction());
    }

    /** Every function bound so far, as instances. */
    public static List<FunctionDefinition> all() {
        final List<FunctionDefinition> all = new java.util.ArrayList<>(groupA());
        all.addAll(groupB());
        return List.copyOf(all);
    }
}
