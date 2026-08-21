<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Computed element and attribute names, PI and comment emission, deep copy-of. -->
  <xsl:template match="/feed">
    <out>
      <xsl:processing-instruction name="generated">by-case</xsl:processing-instruction>
      <xsl:comment>names are data</xsl:comment>
      <xsl:apply-templates/>
    </out>
  </xsl:template>
  <xsl:template match="item">
    <xsl:element name="{@type}">
      <xsl:attribute name="sev-{@level}">yes</xsl:attribute>
      <xsl:value-of select="body"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="raw">
    <xsl:copy-of select="keep"/>
  </xsl:template>
  <xsl:template match="text()"/>
</xsl:stylesheet>
