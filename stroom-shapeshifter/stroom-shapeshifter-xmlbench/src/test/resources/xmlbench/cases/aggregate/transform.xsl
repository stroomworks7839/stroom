<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Aggregation, and tokenize's sequence shape. XSLT folds a node-set it can revisit; the
       byte engine folds a store it accumulated on the way past. min and max are numeric here
       because XSLT atomises untyped attributes to double - the challenger says as:number to
       mean the same thing, since uncast it would order by string form and answer 30 for the
       minimum of 10 and 30. -->
  <xsl:template match="/rows"><s><n><xsl:value-of select="count(row)"/></n><t><xsl:value-of select="sum(row/@v)"/></t><a><xsl:value-of select="avg(row/@v)"/></a><lo><xsl:value-of select="min(row/@v)"/></lo><hi><xsl:value-of select="max(row/@v)"/></hi><d><xsl:for-each select="distinct-values(row/@k)"><k><xsl:value-of select="."/></k></xsl:for-each></d><p><xsl:for-each select="tokenize('a|b|c', '\|')"><i><xsl:value-of select="."/></i></xsl:for-each></p></s></xsl:template>
</xsl:stylesheet>
