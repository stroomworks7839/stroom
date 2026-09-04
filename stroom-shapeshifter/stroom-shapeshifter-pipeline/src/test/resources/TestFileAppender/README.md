# TestFileAppender, with Shapeshifter

A mirror of `stroom-pipeline/src/test/resources/TestFileAppender`, Stroom's own full-pipeline
fixture: the same Apache log input (`TestFileAppender.in`, 200 lines, 59 of them requests for
`/bad` that the original pipeline's schema filter drops) and the same two goldens
(`TestFileAppender_Text.out`, `TestFileAppender_XML.out`). What differs is the pipeline:
Stroom's runs a DS3 configuration, two XSLT stylesheets, a schema filter and a record output
filter; these run one Shapeshifter configuration.

- `TestFileAppender_Text.shapeshifter.json` + `_Text_Pipeline.json` — parser, `TextWriter`,
  `FileAppender`; a text configuration; the file is the text golden byte for byte.
- `TestFileAppender_XML.shapeshifter.json` + `_XML_Pipeline.json` — parser, `XMLWriter`,
  `FileAppender`; a structured configuration (`element`, `attribute`, `namespace`); the file is
  the XML golden as a document — the `XMLWriter`'s whitespace is its own.
- `TestFileAppender_Filter.shapeshifter.json` + `_Filter_Pipeline.json` — `XMLParser`,
  `ShapeshifterFilter`, `TextWriter`, `FileAppender`, over the XML golden as input; the filter
  reads the image of the events and writes the text golden back, byte for byte.

Run by `FullPipelineTest` through a real `PipelineFactory` (`stroom.pipeline.factory.ModulePipelines`,
test sources), with no Stroom behind it: elements built by hand, documents in a map.
