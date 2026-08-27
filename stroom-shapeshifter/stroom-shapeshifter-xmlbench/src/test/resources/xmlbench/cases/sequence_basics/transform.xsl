<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Iteration with positional context. The challenger reaches the same output by a
       different route, which is the point: XSLT walks the tree a second time, while the
       byte engine accumulates a sequence as the records go past and walks that after the
       level has finished - the shape design/16 section 1 found already possible. -->
  <xsl:template match="/items"><t><xsl:apply-templates select="item"/></t></xsl:template>
  <xsl:template match="item"><e p="{position()}" of="{last()}"><xsl:if test="position() = 1">[</xsl:if><xsl:value-of select="@v"/><xsl:if test="position() = last()">]</xsl:if></e></xsl:template>
</xsl:stylesheet>
