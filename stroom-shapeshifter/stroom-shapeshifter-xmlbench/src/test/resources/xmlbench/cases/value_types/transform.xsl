<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="xs">
  <!-- The casting rules a value meets on its way into arithmetic: a numeric reading, an
       absent one, and the whole-Real rendering. castable-as guards mirror the challenger's
       absent-makes-absent rule; the empty branches serialize self-closed on both sides. -->
  <xsl:template match="/vals">
    <vt><xsl:apply-templates select="val"/></vt>
  </xsl:template>
  <xsl:template match="val">
    <v>
      <xsl:choose>
        <xsl:when test="@x castable as xs:double">
          <n><xsl:value-of select="number(@x)"/></n>
          <s><xsl:value-of select="number(@x) + 1"/></s>
          <fl><xsl:value-of select="floor(number(@x))"/></fl>
        </xsl:when>
        <xsl:otherwise><n/><s/><fl/></xsl:otherwise>
      </xsl:choose>
    </v>
  </xsl:template>
</xsl:stylesheet>
