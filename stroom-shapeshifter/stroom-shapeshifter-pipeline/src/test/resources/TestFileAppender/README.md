# TestFileAppender, with Shapeshifter

A mirror of `stroom-pipeline/src/test/resources/TestFileAppender`, Stroom's own full-pipeline
fixture: the same Apache log input (`TestFileAppender.in`, 200 lines, 59 of them requests for
`/bad` that the original pipeline's schema filter drops) and the same two goldens
(`TestFileAppender_Text.out`, `TestFileAppender_XML.out`). What differs is where the shape comes
from: Stroom's pipelines run a DS3 configuration and a stylesheet in front of the record count
filter, the split filter, the schema filter, the record output filter, the second record count
filter, the writer and the appender; these keep that whole chain and put one Shapeshifter
configuration where the DS3 configuration and the stylesheet were. As in Stroom's, the
configuration emits `Eventy` for the `/bad` requests, and it is the schema filter that marks
them and the record output filter that drops them — 200 read, 141 written, 59 errors.

- `TestFileAppender_Text.shapeshifter.json` + `_Text_Pipeline.json` — parser, the chain,
  `TextWriter`, `FileAppender`; the event-logging tree with a newline text node in each `Event`,
  as Stroom's text stylesheet builds it; the file is the text golden byte for byte (the newline
  text nodes are delivered as written — E38).
- `TestFileAppender_XML.shapeshifter.json` + `_XML_Pipeline.json` — parser, the chain,
  `XMLWriter`, `FileAppender`; the file is the XML golden as a document — the `XMLWriter`'s
  whitespace is its own.
- `TestFileAppender_Filter.shapeshifter.json` + `_Filter_Pipeline.json` — `XMLParser`, the record
  count and split filters, `ShapeshifterFilter` where the stylesheet was, the rest of the chain,
  `XMLWriter`, `FileAppender`; the input is the records document Stroom's own DS3 produces from
  the log with `TestFileAppender.ds3.xml` (built at test time through the image writer); the
  filter reads its image and builds the events; the file is the XML golden as a document.
- `event-logging-v3.0.0.xsd` — the schema the schema filter validates against, as the
  event-logging content pack declares it (`event-logging:3`, group `EVENTS`).

Run by `FullPipelineTest` through a real `PipelineFactory` (`stroom.pipeline.factory.ModulePipelines`,
test sources), with no Stroom behind it: elements built by hand, documents and schemas in lists, the schema
cache Stroom's own over a mocked store.
