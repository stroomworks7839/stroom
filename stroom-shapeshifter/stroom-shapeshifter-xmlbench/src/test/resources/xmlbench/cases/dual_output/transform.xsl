<?xml version="1.0" encoding="UTF-8"?>
<!-- The output-routing wall (design/14, D10/E15): one input, two outputs. Adapted with
     disclosure from the appender family (*_XML.xsl / *_Text.xsl emit the same source two
     ways via two pipelines); here collapsed to XSLT's native routing, xsl:result-document,
     with the stroom:format-date dependency dropped. The engine has one sink today, so no
     challenger exists — the same records rendered as XML on the primary output and as
     comma-separated text on a secondary document is exactly the job the routing gap blocks. -->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xpath-default-namespace="records:2">

  <xsl:template match="/records">
    <events>
      <xsl:apply-templates select="record" mode="xml"/>
    </events>
    <xsl:result-document href="summary.txt" method="text">
      <xsl:apply-templates select="record" mode="text"/>
    </xsl:result-document>
  </xsl:template>

  <xsl:template match="record" mode="xml">
    <event host="{data[@name = 'host']/@value}"
           user="{data[@name = 'user']/@value}"
           action="{data[@name = 'action']/@value}"/>
  </xsl:template>

  <xsl:template match="record" mode="text">
    <xsl:value-of select="data[@name = 'user']/@value"/>
    <xsl:text>,</xsl:text>
    <xsl:value-of select="data[@name = 'action']/@value"/>
    <xsl:text>&#10;</xsl:text>
  </xsl:template>
</xsl:stylesheet>
