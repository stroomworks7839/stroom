<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Arithmetic, member by member. Every literal is exact in binary, so Saxon's doubles
       and the challenger's exact integer arithmetic render identically. -->
  <xsl:template match="/orders">
    <res><xsl:apply-templates select="order"/></res>
  </xsl:template>
  <xsl:template match="order">
    <o id="{@id}">
      <total><xsl:value-of select="@qty * @price"/></total>
      <plus><xsl:value-of select="@qty + 2"/></plus>
      <less><xsl:value-of select="@qty - 1"/></less>
      <unit><xsl:value-of select="@price div 2"/></unit>
      <m><xsl:value-of select="@qty mod 3"/></m>
      <r><xsl:value-of select="round(@weight)"/></r>
      <f><xsl:value-of select="floor(@weight)"/></f>
      <c><xsl:value-of select="ceiling(@weight)"/></c>
      <a><xsl:value-of select="abs(@weight)"/></a>
    </o>
  </xsl:template>
</xsl:stylesheet>
