<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- The function library, member by member. -->
  <xsl:template match="/rows">
    <t><xsl:apply-templates select="row"/></t>
  </xsl:template>
  <xsl:template match="row">
    <r>
      <up><xsl:value-of select="upper-case(substring-before(@v, '|'))"/></up>
      <lo><xsl:value-of select="lower-case(substring-after(@v, '|'))"/></lo>
      <ns><xsl:value-of select="normalize-space(@pad)"/></ns>
      <tr><xsl:value-of select="translate(@code, '-', '.')"/></tr>
      <sub><xsl:value-of select="substring(@v, 1, 3)"/></sub>
      <num><xsl:value-of select="number(@n)"/></num>
      <has><xsl:value-of select="contains(@v, 'BETA')"/></has>
      <len><xsl:value-of select="string-length(@v)"/></len>
      <sb><xsl:value-of select="substring-before(@code, '-')"/></sb>
      <sa><xsl:value-of select="substring-after(@code, '-')"/></sa>
      <fn><xsl:value-of select="format-number(@n, '#,##0.00')"/></fn>
    </r>
  </xsl:template>
</xsl:stylesheet>
