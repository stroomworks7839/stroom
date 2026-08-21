<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- A captured value sub-parsed by regex: analyze-string with match groups. -->
  <xsl:template match="/ms">
    <pairs><xsl:apply-templates select="m"/></pairs>
  </xsl:template>
  <xsl:template match="m">
    <xsl:analyze-string select="@kv" regex="([a-z]+)=([0-9]+);?">
      <xsl:matching-substring>
        <p k="{regex-group(1)}" v="{regex-group(2)}"/>
      </xsl:matching-substring>
    </xsl:analyze-string>
  </xsl:template>
</xsl:stylesheet>
