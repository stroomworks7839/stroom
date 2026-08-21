<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- group-starting-with over a flat stream, with the group's position in the output. -->
  <xsl:template match="/log">
    <sections>
      <xsl:for-each-group select="*" group-starting-with="h">
        <section name="{@name}" n="{position()}">
          <xsl:for-each select="current-group()[self::e]">
            <i><xsl:value-of select="."/></i>
          </xsl:for-each>
        </section>
      </xsl:for-each-group>
    </sections>
  </xsl:template>
</xsl:stylesheet>
