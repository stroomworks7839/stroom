package stroom.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import stroom.pipeline.destination.AppenderConfig;
import stroom.pipeline.filter.XmlSchemaConfig;
import stroom.pipeline.filter.XsltConfig;
import stroom.pipeline.refdata.store.RefDataStoreConfig;
import stroom.util.shared.IsConfig;
import stroom.util.xml.ParserConfig;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PipelineConfig implements IsConfig {
    private AppenderConfig appenderConfig;
    private ParserConfig parserConfig;
    private RefDataStoreConfig refDataStoreConfig;
    private XmlSchemaConfig xmlSchemaConfig;
    private XsltConfig xsltConfig;

    public PipelineConfig() {
        appenderConfig = new AppenderConfig();
        parserConfig = new ParserConfig();
        refDataStoreConfig = new RefDataStoreConfig();
        xmlSchemaConfig = new XmlSchemaConfig();
        xsltConfig = new XsltConfig();
    }

    @Inject
    public PipelineConfig(final AppenderConfig appenderConfig,
                          final ParserConfig parserConfig,
                          final RefDataStoreConfig refDataStoreConfig,
                          final XmlSchemaConfig xmlSchemaConfig,
                          final XsltConfig xsltConfig) {
        this.appenderConfig = appenderConfig;
        this.parserConfig = parserConfig;
        this.refDataStoreConfig = refDataStoreConfig;
        this.xmlSchemaConfig = xmlSchemaConfig;
        this.xsltConfig = xsltConfig;
    }

    @JsonProperty("appender")
    public AppenderConfig getAppenderConfig() {
        return appenderConfig;
    }

    public void setAppenderConfig(final AppenderConfig appenderConfig) {
        this.appenderConfig = appenderConfig;
    }

    @JsonProperty("parser")
    public ParserConfig getParserConfig() {
        return parserConfig;
    }

    public void setParserConfig(final ParserConfig parserConfig) {
        this.parserConfig = parserConfig;
    }

    @JsonProperty("referenceData")
    public RefDataStoreConfig getRefDataStoreConfig() {
        return refDataStoreConfig;
    }

    public void setRefDataStoreConfig(final RefDataStoreConfig refDataStoreConfig) {
        this.refDataStoreConfig = refDataStoreConfig;
    }

    @JsonProperty("xmlSchema")
    public XmlSchemaConfig getXmlSchemaConfig() {
        return xmlSchemaConfig;
    }

    public void setXmlSchemaConfig(final XmlSchemaConfig xmlSchemaConfig) {
        this.xmlSchemaConfig = xmlSchemaConfig;
    }

    @JsonProperty("xslt")
    public XsltConfig getXsltConfig() {
        return xsltConfig;
    }

    public void setXsltConfig(final XsltConfig xsltConfig) {
        this.xsltConfig = xsltConfig;
    }
}
