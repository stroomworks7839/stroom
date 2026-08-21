<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Non-adjacent grouping: nothing can be emitted until everything has been seen. -->
  <xsl:template match="/orders">
    <cats>
      <xsl:for-each-group select="order" group-by="@cat">
        <cat name="{current-grouping-key()}" count="{count(current-group())}">
          <xsl:for-each select="current-group()">
            <o><xsl:value-of select="@id"/></o>
          </xsl:for-each>
        </cat>
      </xsl:for-each-group>
    </cats>
  </xsl:template>
</xsl:stylesheet>
