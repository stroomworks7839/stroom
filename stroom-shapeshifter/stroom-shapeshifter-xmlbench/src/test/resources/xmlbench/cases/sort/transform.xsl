<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Two orderings of the same input. The first sorts on a text key and a numeric one,
       descending, with a tie on team+score that both engines must break the same way -
       xsl:sort is stable and so is the challenger's, over an ascending index, so the tie
       keeps data order. The second shows the default: no data-type is a string ordering,
       where 9 sorts after 10. -->
  <xsl:template match="/people"><o><two><xsl:for-each select="p"><xsl:sort select="@team"/><xsl:sort select="@score" data-type="number" order="descending"/><n><xsl:value-of select="@name"/></n></xsl:for-each></two><text><xsl:for-each select="p"><xsl:sort select="@score"/><n><xsl:value-of select="@score"/></n></xsl:for-each></text></o></xsl:template>
</xsl:stylesheet>
