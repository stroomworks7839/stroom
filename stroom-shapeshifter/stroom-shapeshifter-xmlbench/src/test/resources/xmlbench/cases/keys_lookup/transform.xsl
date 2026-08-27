<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- xsl:key and key(), which nothing in the repository's 97 stylesheets uses - so this is
       authored rather than adapted (design/16 section 8). The lookups deliberately include a
       miss: key() on a value with no entry is an empty sequence, not an error, and both
       engines must render that as an empty element rather than as anything at all. -->
  <xsl:key name="by_cat" match="order" use="@cat"/>
  <xsl:template match="/data"><r><xsl:for-each select="want"><g c="{@c}"><xsl:for-each select="key('by_cat', @c)"><o><xsl:value-of select="@id"/></o></xsl:for-each></g></xsl:for-each></r></xsl:template>
</xsl:stylesheet>
