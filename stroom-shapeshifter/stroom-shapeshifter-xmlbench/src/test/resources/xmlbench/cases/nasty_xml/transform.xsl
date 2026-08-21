<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <!-- Attribute value templates, descendant pulls from deep nesting, CDATA flattened to
       escaped text, comments dropped, empty elements self-closed by the serializer. -->
  <xsl:template match="/audit">
    <summary>
      <xsl:apply-templates select="batch/entry"/>
    </summary>
  </xsl:template>

  <xsl:template match="entry">
    <item batch="{../@id}" seq="{@seq}" system="{meta/source/system/name}"
          tier="{meta/source/system/tier}">
      <sql><xsl:value-of select="detail"/></sql>
      <note><xsl:value-of select="note"/></note>
      <xsl:if test="context">
        <deep><xsl:value-of select="context/a/b/c/d/e"/></deep>
      </xsl:if>
    </item>
  </xsl:template>
</xsl:stylesheet>
